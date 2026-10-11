import { useEffect, useRef, useState } from 'react';
import { Api, ApiError } from './api';
import { read_usage, usage_checked_time, usage_count_keys, usage_timezone_supported, type UsagePeriod, type UsageSnapshot } from './usage';

export interface UsagePanelProps {
  api: Api; sessionKey: string | number; onUnauthorized: () => void;
  /** This owner-only read remains independent of ordinary write/navigation locks. */
  selectionDisabled?: boolean;
}
interface Session { api: Api; key: string | number; alive: boolean; authorized: boolean; revision: number; snapshot: UsageSnapshot | null }
interface View { session: Session; snapshot: UsageSnapshot | null; busy: boolean; failed: boolean }
const labels = { measured: '계측 기록 있음', pending: '시작 대기', in_progress: '진행 중', unstarted: '시작하지 않고 종료', uncertain: '결과 불확실', legacy_unavailable: '과거 계측 자료 없음' };
const number = (value: number) => value.toLocaleString('ko-KR');

function Period({ value, title, verified }: { value: UsagePeriod; title: string; verified: boolean }) {
  const measured = value.measured, reserved = value.conservative_reserved;
  return <section aria-label={title}>
    <h3>{title} · {value.local_key}</h3>
    <dl>
      <dt>기간 시작 (포함, 한국시간)</dt><dd><time dateTime={value.start_at}>{usage_checked_time(value.start_at)}</time></dd>
      <dt>기간 종료 (제외, 한국시간)</dt><dd><time dateTime={value.end_at}>{usage_checked_time(value.end_at)}</time></dd>
      <dt>전체 요청 수</dt><dd>{number(value.request_count)}건</dd>
    </dl>
    <h4>서로 겹치지 않는 요청 상태</h4>
    <dl>{usage_count_keys.map(key => <div key={key}><dt>{labels[key]}</dt><dd>{number(value.counts[key])}건</dd></div>)}</dl>
    <section aria-label={`${title} 계측된 사용량`}>
      <h4>계측된 요청의 실제 사용량</h4>
      <dl>
        <dt>계측 요청 수</dt><dd>{number(measured.request_count)}건</dd>
        <dt>입력 토큰 합계</dt><dd>{number(measured.input_tokens)}토큰</dd>
        <dt>출력 토큰 합계</dt><dd>{number(measured.output_tokens)}토큰</dd>
        <dt>연산 시간 합계</dt><dd>{number(measured.compute_ms)}ms</dd>
        <dt>관측 최고 메모리 (최댓값)</dt><dd>{measured.peak_memory_bytes === null ? '계측 자료 없음' : `${number(measured.peak_memory_bytes)}바이트`}</dd>
        <dt>금전 비용</dt><dd>확인할 수 없음 (비용 자료 미제공)</dd>
      </dl>
      {measured.request_count === 0 && <p>계측된 요청이 없습니다. 합계 0은 확인된 계측 기록의 합이며, 미계측 요청의 실제 사용량이 0이라는 뜻은 아닙니다.</p>}
    </section>
    <section aria-label={`${title} 보수적 예약 한도`}>
      <h4>보수적으로 유지된 예약 한도</h4>
      <dl>
        <dt>예약 한도가 유지된 요청 수</dt><dd>{number(reserved.request_count)}건</dd>
        <dt>예약 연산 시간 상한 합계</dt><dd>{number(reserved.max_compute_ms)}ms</dd>
        <dt>예약 출력 토큰 상한 합계</dt><dd>{number(reserved.max_output_tokens)}토큰</dd>
      </dl>
      <p>계측 완료 요청의 예약 한도도 포함될 수 있습니다. 실제 사용량과 합산하지 않으며, 남은 할당량을 뜻하지 않습니다.</p>
    </section>
    {verified && value.request_count === 0 && <p>이 확인 시점에 해당 기간의 예약 요청 기록이 없습니다.</p>}
  </section>;
}

/** Parent mounts only after explicit open and unmounts on close/logout/identity changes. */
export function UsagePanel({ api, sessionKey, onUnauthorized }: UsagePanelProps) {
  const live = useRef({ api, sessionKey, onUnauthorized }); live.current = { api, sessionKey, onUnauthorized };
  const session = useRef<Session | null>(null);
  const [view, setView] = useState<View | null>(null);
  const same = (s: Session) => s.alive && s.authorized && session.current === s && live.current.api === s.api && live.current.sessionKey === s.key;
  async function refresh(s: Session) {
    if (!same(s)) return;
    const revision = ++s.revision;
    setView({ session: s, snapshot: s.snapshot, busy: true, failed: false });
    try {
      const raw = await s.api.request<unknown>('/usage');
      if (!same(s) || revision !== s.revision) return;
      const snapshot = read_usage(raw); s.snapshot = snapshot;
      setView({ session: s, snapshot, busy: false, failed: false });
    } catch (error) {
      if (!same(s)) return;
      // A same-session 401 revokes even when a more recent read has superseded it.
      if (error instanceof ApiError && error.status === 401) {
        s.authorized = false; s.revision++; s.snapshot = null;
        setView(null); s.api.clear(); live.current.onUnauthorized(); return;
      }
      if (revision === s.revision) setView({ session: s, snapshot: s.snapshot, busy: false, failed: true });
    }
  }
  useEffect(() => {
    const s: Session = { api, key: sessionKey, alive: true, authorized: true, revision: 0, snapshot: null };
    session.current = s; setView(null); void refresh(s);
    return () => { s.alive = false; s.authorized = false; s.revision++; s.snapshot = null; if (session.current === s) session.current = null; };
  }, [api, sessionKey]);
  const visible = view && same(view.session) ? view : null;
  const data = visible?.snapshot;
  const authorized = session.current !== null && same(session.current);
  return <section className="panel usage" aria-label="사용량 요약" aria-busy={visible?.busy ?? false}>
    <h2>사용량</h2>
    <p>계정 소유자의 저장된 사용량 요약을 읽기만 합니다. 열기와 새로고침은 실행을 시작하거나 재시도하지 않습니다.</p>
    <button type="button" className="secondary" disabled={!authorized} onClick={() => { if (session.current) void refresh(session.current); }}>사용량 새로고침</button>
    {visible?.busy && <p role="status">{data ? '사용량을 다시 확인하고 있습니다. 아래에는 이전 확인 결과가 표시됩니다.' : '사용량을 확인하고 있습니다…'}</p>}
    {visible?.failed && <p role="alert">{data ? '사용량을 다시 확인하지 못했습니다. 아래 이전 결과는 오래된 정보이며 재검증되지 않았습니다. 새로고침으로 다시 조회할 수 있습니다.' : '사용량을 확인하지 못했습니다. 기록의 유무와 실제 사용량은 알 수 없습니다. 새로고침으로 다시 조회할 수 있습니다.'}</p>}
    {data && <>
      <p>확인 시각 (한국시간): <time dateTime={data.checked_at}>{usage_checked_time(data.checked_at)}</time></p>
      <p>기간 집계 시간대: {data.timezone} (프로필 설정). 오늘·이번 달은 이 시간대의 날짜이며, 모든 시각은 한국시간(KST)으로 표시합니다.</p>
      {!usage_timezone_supported(data.timezone) && <p>브라우저에서 이 프로필 시간대의 현지 날짜를 변환할 수 없습니다. 집계 날짜·월은 서버의 값을 유지하고 시각은 한국시간(KST)으로 표시합니다.</p>}
      <p>할당량 기준 시간대: {data.quota_timezone}. 프로필 시간대의 오늘·이번 달 집계 기간과 다를 수 있습니다.</p>
      <p>기간 귀속 기준: 예약 생성 시각 (RESERVATION_CREATED_AT). 나중에 완료된 요청도 예약이 생성된 기간에 포함됩니다.</p>
      <p>계측 자료가 없는 과거 요청과 대기·진행 중·불확실한 요청의 실제 사용량은 알 수 없으며 계측 합계에 포함되지 않습니다. 금전 비용과 전기 요금은 추정하지 않습니다.</p>
      <p>입력·출력 토큰과 연산 시간은 계측 기록의 합계입니다. 관측 최고 메모리는 기록 중 최댓값이며 합계가 아닙니다.</p>
      <Period value={data.day} title="오늘 사용량" verified={!visible.busy && !visible.failed} />
      <Period value={data.month} title="이번 달 사용량" verified={!visible.busy && !visible.failed} />
    </>}
  </section>;
}
