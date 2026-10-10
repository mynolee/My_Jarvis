import { ApiError } from './api';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NONBLANK = /[^\u0009-\u000D\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]/u;

export interface ProjectFields { name: string; description: string }
export interface Project extends ProjectFields { id: string; active: boolean; version: number; created_at: string }
export interface ProjectPage { items: Project[]; next_cursor: string | null }
export const empty_project = (): ProjectFields => ({ name: '', description: '' });
export function valid_project_fields(value: ProjectFields): boolean {
  return typeof value.name === 'string' && !/[\uD800-\uDFFF]/u.test(value.name) && NONBLANK.test(value.name) && !/[\u0000-\u001F\u007F-\u009F]/.test(value.name) && [...value.name].length <= 200 && typeof value.description === 'string' && !/[\uD800-\uDFFF]/u.test(value.description) && !value.description.includes('\u0000') && [...value.description].length <= 4000;
}
function valid_project_time(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(value) || !Number.isFinite(Date.parse(value))) return false;
  return new Date(value).toISOString().slice(0, 19) === value.slice(0, 19) && !value.startsWith('0000');
}
function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) throw new ApiError('INVALID_PROJECT_RESPONSE');
  return value as Record<string, unknown>;
}
/** Owner is derived by the authenticated server, never supplied in mutation bodies. */
export function read_project(value: unknown): Project {
  const data = object(value, ['id', 'name', 'description', 'active', 'version', 'created_at']);
  if (typeof data.id !== 'string' || !UUID.test(data.id) || typeof data.name !== 'string' || typeof data.description !== 'string' || !valid_project_fields({ name: data.name, description: data.description }) || data.active !== true || data.version !== 1 || typeof data.created_at !== 'string' || !valid_project_time(data.created_at)) throw new ApiError('INVALID_PROJECT_RESPONSE');
  return { id: data.id, name: data.name, description: data.description, active: true, version: data.version as number, created_at: data.created_at };
}
export function read_project_page(value: unknown): ProjectPage {
  const data = object(value, ['items', 'next_cursor']);
  if (!Array.isArray(data.items) || data.items.length > 100 || (data.next_cursor !== null && (typeof data.next_cursor !== 'string' || !data.next_cursor || data.next_cursor.length > 1024))) throw new ApiError('INVALID_PROJECT_RESPONSE');
  const items = data.items.map(item => read_project(item));
  if (new Set(items.map(item => item.id)).size !== items.length) throw new ApiError('INVALID_PROJECT_RESPONSE');
  return { items, next_cursor: data.next_cursor as string | null };
}
export function read_project_receipt(value: unknown, expected: ProjectFields): Project {
  const data = object(value, ['status', 'project']);
  if (data.status !== 'COMMITTED') throw new ApiError('INVALID_PROJECT_RESPONSE');
  const project = read_project(data.project);
  if (project.name !== expected.name || project.description !== expected.description) throw new ApiError('INVALID_PROJECT_RESPONSE');
  return project;
}
