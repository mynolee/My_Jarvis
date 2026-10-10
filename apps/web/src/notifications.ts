import { reminder_fail, reminder_integer, reminder_instant_us, reminder_object, reminder_time, reminder_uuid, valid_reminder_text } from './schedules';
export interface InboxNotification { id: string; occurrence_id: string; schedule_id: string; schedule_version: number; project_id: string | null; title: string; text: string; planned_fire_at_utc: string; created_at: string; opened_at: string | null; status: 'CREATED' | 'OPENED'; lateness_seconds: number }
export function read_notification(v: unknown): InboxNotification {
  const d = reminder_object(v, ['id', 'occurrence_id', 'schedule_id', 'schedule_version', 'project_id', 'title', 'text', 'planned_fire_at_utc', 'created_at', 'opened_at', 'status', 'lateness_seconds']);
  if (!reminder_uuid(d.id) || !reminder_uuid(d.occurrence_id) || !reminder_uuid(d.schedule_id) || !reminder_integer(d.schedule_version) || !(d.project_id === null || reminder_uuid(d.project_id)) || !valid_reminder_text(d.title, 200) || !valid_reminder_text(d.text, 2000) || !reminder_time(d.planned_fire_at_utc) || !reminder_time(d.created_at) || !reminder_integer(d.lateness_seconds, 0, 3600) || reminder_instant_us(d.created_at) < reminder_instant_us(d.planned_fire_at_utc) || (d.status === 'CREATED' ? d.opened_at !== null : d.status !== 'OPENED' || !reminder_time(d.opened_at) || reminder_instant_us(d.opened_at) < reminder_instant_us(d.created_at))) return reminder_fail();
  const elapsed = reminder_instant_us(d.created_at) - reminder_instant_us(d.planned_fire_at_utc);
  if (elapsed > 3600000000n || elapsed / 1000000n !== BigInt(d.lateness_seconds)) return reminder_fail();
  return d as unknown as InboxNotification;
}
/** Only the explicit open timestamp/status may change after delivery. */
export function same_notification_content(a: InboxNotification, b: InboxNotification): boolean { return (['id', 'occurrence_id', 'schedule_id', 'schedule_version', 'project_id', 'title', 'text', 'planned_fire_at_utc', 'created_at', 'lateness_seconds'] as const).every(k => a[k] === b[k]); }
