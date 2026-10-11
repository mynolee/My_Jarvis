# Copy saved study feedback into an independent task

This feature offers an explicit text-copy handoff from saved study feedback to the existing internal task editor. The behavior below describes the implementation. Verification must be assessed for the reviewed revision; this document does not establish publication or deployment.

## Choose, copy, review, and save

1. Open a saved feedback result whose status is `ACCEPTED` and whose output is retained. Choose one to three checklist items from one result. The checkboxes select content for a draft; they do not record progress or completion. A retained result can be copied from an open or owner-closed study, including when model execution is visibly unconfirmed or the model is currently unavailable.
2. Click **Copy selected items to task draft**. The app reads the saved study and then its saved feedback again, in that order. It checks the original source identities, versions, timestamps, item positions, and exact selected text. Missing, deleted, stale, malformed, or unavailable data produces no copy. There is no cached fallback or automatic retry; a later explicit Copy starts fresh reads.
3. Review the incoming preview in the task panel. Importing requires **Use this review draft**, or an explicit replacement action if another unsaved task edit exists. Dismissal makes no task change. An incoming copy does not silently replace a draft, review, issued intent, or conflict.
4. Edit the ordinary task fields. The initial title identifies the study question, and the description contains only the selected checklist lines. Priority starts at `NORMAL`; deadline and estimate are empty. The original study project is preserved, including a personal task with no project. Choosing a project for a future conversation does not retarget this draft.
5. Review the edited fields, then click **Request task intent**. This records the exact reviewed draft in an encrypted server-side task-intent record. It does not create a task. A separate **Apply server-bound task change** click uses that issued intent to create the task. The intent request and task mutation have distinct request keys.

Copying, importing, and reviewing do not create a task, send a message, request fresh feedback, run a model, or create a reminder. Checklist content, including HTML, URLs, or instruction-like text, is displayed as inert text.

Confirmed creation starts at `TODO`, version 1. A successful response must match the issued target and approved task fields; equivalent deadline time zones are compared as the same instant at microsecond precision. An unverified response is not reported as success.

## Independent-copy and deletion limits

The temporary **Source read when copied** display identifies the historical source observation. Study/feedback source identifiers and check times stay outside editable task arguments and are not appended to the task description. After creation, the result is an ordinary independent task.

The copy is editable. It is not server-attested provenance, an immutable quotation, source-linked duplicate prevention, or an atomic promise that the source remains live at task creation. The unchanged task service authorizes the reviewed task fields, without rechecking the study source at Apply. A still-valid task intent can therefore create its independent task after the source conversation was deleted elsewhere.

Deleting the study conversation removes access to its study feedback. It does not erase independently stored tasks, encrypted task-intent records, or encrypted task-mutation response copies. Requesting an intent already stores the draft even if Apply is never clicked. Back, cancel, or clearing a local draft makes no new task change and does not revoke or erase an existing server record. This feature adds no retention period or source-linked cleanup.

When this page learns of source deletion or deliberately hides source content for deletion, it discards the local incoming copy and uncommitted imported review or intent. It then sends no new Apply from that discarded review. Sign-out, authentication/API replacement, page hiding, and an actual source-selection change also clear transient copied text. A request already sent may still commit; navigation is not cancellation.

Ordinary same-source refreshes after import do not discard the independent task operation. Temporary study-display clearing caused by the task's own intent or Apply request also leaves that operation intact. These display changes must not create a replacement request or lose pending recovery information.

## Unconfirmed outcomes

`UNKNOWN` means the server outcome could not be verified. The app clears the copied prose, source label, review, and locally held intent, and keeps only the original content-free operation and recovery identity. New task writes stay fenced. It does not retry intent issuance or Apply automatically, or allocate a replacement key to guess the outcome.

For an uncertain task mutation, **Check task outcome** reads the original mutation receipt and then the current task separately. An imported-create receipt must describe the original target at `TODO`, version 1. The separately read current task may be newer or completed because of a later explicit edit. A missing receipt, `NOT_OBSERVED`, malformed data, or a failed current-task read is inconclusive and does not unlock the operation. Intent issuance has no equivalent receipt-recovery endpoint; an uncertain issuance is disclosed without claiming that nothing was stored.

If session or view changes lose recovery context, a content-free warning says an earlier request may still have committed. The warning is advisory and does not itself govern write admission. It remains visible for the current mounted app's lifetime, including in-app refresh, sign-out/sign-in, and later successful work. It is not saved to browser storage and is not guaranteed to survive a full page reload or app remount.

Transient source copies and recovery information are not written to browser storage, history state, URLs, or logs. No report or download is introduced. Clearing displayed text is not a claim to erase bytes already sent in a request or retained in independent server records.

## Completion stays explicit

Selecting every checklist item, receiving `CORRECT` feedback, closing a study, finishing a model run, or creating a task does not mark the task complete. Completion uses the existing explicit task-status edit, version-bound intent, and Apply flow. Deliberately reopening a task remains available. This handoff does not infer mastery, learning level, urgency, deadlines, or time required, and it does not add natural-language task-command recognition.

## Verification status

- Static behavior and test-coverage review: performed; not runtime evidence.
- Local focused synthetic UI/parser tests have passed. A focused result does not establish the full aggregate, configured build, or exact-revision CI result; those require their own recorded evidence.
- Actual Java/JUnit, Java build, and configured TypeScript 7 verification: `NOT_RUN` in this work; their configured CI execution is still required.
- Native browser, keyboard/mobile-layout, and real-model quality checks: `NOT_RUN`.
- Publication, deployment, and exact-published-head CI: not established by this document.

Synthetic component and backend tests, even when subsequently passing, do not substitute for native-browser or model-quality evidence. Update verification status only from results bound to the final reviewed source.
