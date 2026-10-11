import { ApiError } from './api';
import { feedback_study, read_feedback, type FeedbackProjection, type FeedbackStudy, type FeedbackTarget } from './study-feedback';
import { read_durable_study, study_id, type DurableStudy } from './study';
import { task_time_micros, valid_date, valid_fields, type TaskFields } from './tasks';

/** Ephemeral selection context, never a task argument or server capability. */
export interface StudyReviewSelection {
  conversation_id: string; study_session_id: string; lesson_run_id: string;
  question_id: string; input_id: string; attempt_id: string; feedback_request_id: string; feedback_version: number;
  study_version: number; conversation_version: number; feedback_checked_at: string; feedback_updated_at: string;
  selected_items: { index: number; text: string }[];
}
export interface StudyReviewCopy {
  ui_id: string; fields: TaskFields;
  origin: Omit<StudyReviewSelection, 'selected_items'> & { project_id: string | null; study_checked_at: string; selected_indexes: number[] };
}
const invalid = (): never => { throw new ApiError('REVIEW_SOURCE_NOT_VERIFIED'); };
function selection_bounds(selection: StudyReviewSelection) {
  for (const key of ['conversation_id', 'study_session_id', 'lesson_run_id', 'question_id', 'input_id', 'attempt_id', 'feedback_request_id'] as const) if (!study_id(selection[key])) invalid();
  if (selection.feedback_version !== 3 || ![3, 4].includes(selection.study_version) || !Number.isSafeInteger(selection.conversation_version) || selection.conversation_version < 1 || selection.conversation_version > Number.MAX_SAFE_INTEGER - 1 || !valid_date(selection.feedback_checked_at) || !valid_date(selection.feedback_updated_at) || task_time_micros(selection.feedback_updated_at) > task_time_micros(selection.feedback_checked_at)) invalid();
  if (!Array.isArray(selection.selected_items) || selection.selected_items.length < 1 || selection.selected_items.length > 3 || new Set(selection.selected_items.map(item => item.index)).size !== selection.selected_items.length) invalid();
  for (const item of selection.selected_items) if (!Number.isSafeInteger(item.index) || item.index < 0 || item.index > 2 || typeof item.text !== 'string' || !item.text.trim() || [...item.text].length > 500) invalid();
}
export function select_review_items(study: FeedbackStudy, projection: FeedbackProjection, target: FeedbackTarget, indexes: number[]): StudyReviewSelection {
  const request = target.request;
  if (!projection.targets.includes(target) || !request || request.status !== 'ACCEPTED' || request.output_authority !== 'CLOSED' || !request.result || !target.input_id || !target.attempt_id) return invalid();
  const selection: StudyReviewSelection = { conversation_id: projection.conversation_id, study_session_id: projection.study_session_id, lesson_run_id: study.lesson_run_id, question_id: target.question_id, input_id: target.input_id, attempt_id: target.attempt_id, feedback_request_id: request.id, feedback_version: request.feedback_version, study_version: projection.study_version, conversation_version: projection.conversation_version, feedback_checked_at: projection.checked_at, feedback_updated_at: request.updated_at, selected_items: indexes.map(index => ({ index, text: request.result!.review_checklist[index] })) };
  selection_bounds(selection);
  return selection;
}
/** BOTH the displayed feedback and the durable view contribute freshness floors. */
export function read_review_study(raw: unknown, captured: DurableStudy, selection: StudyReviewSelection): DurableStudy {
  selection_bounds(selection);
  const answer = captured.attempts.find(item => item.question_id === selection.question_id);
  if (selection.conversation_id !== captured.conversation_id || selection.study_session_id !== captured.id || selection.lesson_run_id !== captured.lesson_run_id || !answer || answer.input_id !== selection.input_id || answer.attempt_id !== selection.attempt_id) return invalid();
  return read_durable_study(raw, { id: captured.id, conversation_id: captured.conversation_id, lesson_run_id: captured.lesson_run_id, project_id: captured.project_id, minimum_version: Math.max(captured.version, selection.study_version), minimum_conversation_version: Math.max(captured.conversation_content_version, selection.conversation_version), checked_after: task_time_micros(captured.checked_at) > task_time_micros(selection.feedback_checked_at) ? captured.checked_at : selection.feedback_checked_at });
}
export function build_review_copy(raw: unknown, study: DurableStudy, selection: StudyReviewSelection, ui_id: string): StudyReviewCopy {
  // Recheck binding even when called independently of the ordered read helper.
  const fresh = read_review_study(study, study, selection), projection = read_feedback(raw, feedback_study(fresh));
  const target = projection.targets.find(item => item.question_id === selection.question_id), request = target?.request;
  if (!study_id(ui_id) || target?.input_id !== selection.input_id || target?.attempt_id !== selection.attempt_id || !request || request.id !== selection.feedback_request_id || request.feedback_version !== selection.feedback_version || request.status !== 'ACCEPTED' || request.output_authority !== 'CLOSED' || !request.result || task_time_micros(request.updated_at) < task_time_micros(selection.feedback_updated_at)) return invalid();
  for (const item of selection.selected_items) if (request.result.review_checklist[item.index] !== item.text) return invalid();
  const question = fresh.lesson.questions.findIndex(item => item.id === selection.question_id);
  if (question < 0 || question > 1) return invalid();
  const fields: TaskFields = { title: `Review study question ${question + 1}`, description: selection.selected_items.map(item => `- ${request.result!.review_checklist[item.index]}`).join('\n'), project_id: fresh.project_id, due_at: null, priority: 'NORMAL', estimated_minutes: null };
  if (!valid_fields(fields)) return invalid();
  const { selected_items, ...source } = selection;
  return Object.freeze({ ui_id, fields: Object.freeze(fields), origin: Object.freeze({ ...source, project_id: fresh.project_id, study_version: projection.study_version, conversation_version: projection.conversation_version, study_checked_at: fresh.checked_at, feedback_checked_at: projection.checked_at, feedback_updated_at: request.updated_at, selected_indexes: Object.freeze(selected_items.map(item => item.index)) as unknown as number[] }) });
}
