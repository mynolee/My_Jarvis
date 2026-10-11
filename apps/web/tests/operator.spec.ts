import { expect, test } from '@playwright/test';
const conversation_id = '11111111-1111-4111-8111-111111111111';
const run_id = '22222222-2222-4222-8222-222222222222';
const approval_id = '33333333-3333-4333-8333-333333333333';
const action_id = '44444444-4444-4444-8444-444444444444';
const poison = '<img src="https://example.invalid/exfil" onerror="alert(1)"><script>alert(1)</script>[link](javascript:alert(1))';
test('mocked HTTP UI flow: login, text-only snapshot, approve once, reload, logout storage', async ({ page }) => {
  let authenticated = false, has_message = false, status = 'WAITING_APPROVAL', decisions = 0;
  const posts: Array<{ url: string; body: unknown }> = [];
  const requests: string[] = []; page.on('request', request => requests.push(request.url()));
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname.replace('/api/v1', '');
    const method = request.method(); let data: unknown = {};
    if (method === 'POST') { expect(request.headers()['x-csrf-token']).toBe('csrf-test'); posts.push({ url: path, body: request.postDataJSON() }); }
    if (path === '/auth/csrf') data = { csrf_token: 'csrf-test' };
    else if (path === '/auth/login') { authenticated = true; data = { csrf_token: 'csrf-test' }; }
    else if (path === '/me') { if (!authenticated) { await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ error: { code: 'UNAUTHENTICATED' } }) }); return; } data = { login_name: 'owner' }; }
    else if (path === '/local-model/status') data = { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: false, status: 'DISABLED' };
    else if (path === '/execution' && method === 'GET') data = { execution_paused: false, version: 1, checked_at: '2026-10-10T15:00:00Z' };
    else if (path === '/auth/logout') authenticated = false;
    else if (path === '/conversations' && method === 'POST') data = { id: conversation_id, title: 'Test', project_id: null, created_at: '2026-10-09T00:00:00Z' };
    else if (path === '/conversations') data = { items: [{ id: conversation_id, title: 'Test', project_id: null, created_at: '2026-10-09T00:00:00Z' }] };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    else if (path.endsWith('/messages')) { if (method === 'POST') { has_message = true; data = { run_id, status }; } else data = { items: has_message ? [{ id: action_id, text: poison, role: 'assistant', run_id, created_at: '2026-10-09T00:00:00Z' }] : [] }; }
    else if (path === `/runs/${run_id}`) data = { id: run_id, status, version: 1, result: status === 'SUCCEEDED' ? 'MOCK verified local result' : null, approval_id, job_id: null };
    else if (path.endsWith('/decision')) { decisions++; status = 'SUCCEEDED'; data = { status: 'APPROVED' }; }
    else if (path === `/approvals/${approval_id}`) data = { id: approval_id, action_id, action_version: 1, payload_hash: 'a'.repeat(64), status: decisions ? 'APPROVED' : 'PENDING', expires_at: '2099-01-01T00:00:00Z', payload: { tool_id: 'calendar.event.create', arguments: { title: poison } } };
    else throw new Error(`Unhandled ${method} ${path}`);
    await route.fulfill({ contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify({ data, request_id: action_id }) });
  });
  await page.goto('/'); await page.getByLabel('Login name').fill('owner'); await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password'); await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByLabel('Message', { exact: true }).fill('/calendar'); await page.getByRole('button', { name: 'Send mock message' }).click();
  await expect(page.getByRole('heading', { name: 'Review exact server snapshot' })).toBeVisible();
  await expect(page.locator('.message')).toContainText(poison); expect(await page.locator('img,iframe').count()).toBe(0);
  await page.getByRole('button', { name: 'Approve exact action' }).dblclick();
  await expect(page.getByText('MOCK verified local result', { exact: true })).toBeVisible(); expect(decisions).toBe(1);
  expect(posts.find(post => post.url.endsWith('/decision'))?.body).toEqual({ decision: 'APPROVE', expected_action_version: 1, payload_hash: 'a'.repeat(64) });
  await page.reload(); await expect(page.getByText('MOCK verified local result', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click(); await expect(page.getByRole('heading', { name: 'Owner sign in' })).toBeVisible();
  expect(await page.evaluate(async () => ({ local: localStorage.length, session: sessionStorage.length, caches: (await caches.keys()).length, databases: (await indexedDB.databases()).length }))).toEqual({ local: 0, session: 0, caches: 0, databases: 0 });
  expect(requests.some(url => url.includes('example.invalid'))).toBe(false);
});

test('uncertain mutation pauses writes and never retries automatically', async ({ page }) => {
  let writes = 0;
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/login')) { writes++; await route.abort('failed'); return; }
    const data = path.endsWith('/auth/csrf') ? { csrf_token: 'csrf-test' } : {};
    await route.fulfill({ status: path.endsWith('/me') ? 401 : 200, contentType: 'application/json', body: JSON.stringify(path.endsWith('/me') ? { error: { code: 'UNAUTHENTICATED' } } : { data }) });
  });
  await page.goto('/'); await page.getByLabel('Login name').fill('owner'); await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password'); await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('UNKNOWN'); expect(writes).toBe(1);
});
