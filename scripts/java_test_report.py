#!/usr/bin/env python3
"""Publish only reviewed Java identities and parsed JUnit outcomes, never XML text.

prepare must run before check.sh in a fresh checkout; emit runs even on failure.
The checked-in inventory is a disclosure allowlist, not execution evidence. Adding
or renaming tests requires an explicit inventory review. Default, zero-argument
JUnit names are supported; parameterized/display names are deliberately unmapped.
No JUnit file, failure message, output, timing, hostname or file path is emitted.
The log format is JSON Lines: java_test_report, ordered java_test_method records,
and java_test_report_end. Consumers must require the end record, sequence/count
agreement, and its SHA-256 of the preceding exact UTF-8 lines (including newlines).
Without that trailer the log is incomplete, regardless of the header's status.
Counts are XML testcase observations, including unmapped cases. observed_methods
counts unique mapped identities; duplicate_observations counts excess mapped
entries. retry_indicators counts XML retry tags, not proven retry attempts.
This report is synthetic component evidence, never native/release acceptance.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
from xml.parsers import expat

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = "apps/core/src/test/java"
REPORT_ROOT = "apps/core/build/test-results/test"
PACKAGE = "dev.myjarvis.core"
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_FILES = 64
MAX_CASES = 10000
MAX_DEPTH = 12
MAX_SOURCE_BYTES = 32 * 1024 * 1024
OUTCOMES = ("passed", "failed", "errors", "skipped")
CONTEXT_ENV = {"checkout_sha": "REPORT_CHECKOUT_SHA", "pr_head_sha": "REPORT_PR_HEAD_SHA",
               "run_id": "GITHUB_RUN_ID", "run_attempt": "GITHUB_RUN_ATTEMPT"}
# Reviewed source names only. Do not learn output identities from XML attributes.
ALLOWED_TESTS = {
    "DurableStudyConcurrencyTest": (
        "simultaneousSameKeyCreatesOneSourceReceiptAuditAndVersionIncrement",
        "twoQuestionsAtOneSnapshotHaveOneWinnerAndNeverSilentlyRebase",
        "answerAndCloseSerializeAndBothExplicitOrderingsRemainLegal",
        "ownerAndDescendantLockWaitsRecheckIdleAndAbsoluteSessionExpiry",
        "blockedReceiptConstraintRechecksExpiryAfterAllBusinessWrites",
        "blockedNonceReservationCannotCommitAnExpiredOwnerAnswer",
        "backwardFinalClockAfterReceiptForcesCompleteRollback",
        "failuresAfterInputTransitionReceiptAndAuditRollBackEveryBusinessRow",
        "answerBeforeDeleteInvalidatesReviewAndDeleteBeforeAnswerNeverRehydrates",
    ),
    "DurableStudyFixtures": (
    ),
    "DurableStudyHttpTest": (
        "realStartAnswerReadCloseAndReceiptRoutesUseNoInferenceOrMessageFallback",
        "authenticationOriginCsrfContentTypeAndIdempotencyHeadersAreMandatory",
        "duplicateJsonFloatsExponentsUnsafeNumbersSurrogatesAndClientAuthorityFailClosed",
        "allReadRoutesRejectBodiesQueriesAndNoncanonicalIdentifiers",
        "answerTextIsUnicodeScalarBoundedAndErrorsNeverEchoContent",
        "deletedSourceRoutesReturnGoneButOriginalLiveReceiptLookupStaysMetadataOnly",
    ),
    "DurableStudyMigrationTest": (
        "everyHistoricalMigrationRetainsItsPublishedBytes",
        "cleanV1ThroughV16CreatesExactlyThreeStudyRelations",
        "v15PreparationsAreRevokedWhileConsumedLedgerAndUsageRemainExact",
        "failedV16RollsBackEarlierDdlAndDoesNotRevokeOldAuthority",
    ),
    "DurableStudyServiceTest": (
        "acceptedLessonTwoExactInputsRestartCloseAndDeletionHaveNoExtraModelWork",
        "duplicateKeysChangedRequestsQuestionConflictsAndStaleSnapshotsDoNotWrite",
        "canonicalStateSurvivesNewLoginButOriginalMutationAuthorityDoesNot",
        "ordinaryMessagesAndContextChangesPreserveHistoricalInputEvidence",
        "pausedExecutionBlocksWritesButDoesNotHideRecordedInputs",
        "rejectedMockClarificationOrSubstitutedSourcesNeverStart",
        "cancelledOrMeasuredClarificationSubstitutedAsSuccessCannotSupplyLessonAuthority",
        "maximumUnicodeInputAndUnknownFieldsRespectDedicatedRequestCap",
        "deletionHeadroomAndUnknownSchemaRefuseBeforeAnyInputAllocation",
        "contentFreeIndexAndNotObservedReadDoNotClaimAWriteOutcome",
    ),
    "StudyDeletionIntegrationTest": (
        "openAndFeedbackPendingStudySourcesDeleteWithoutChangingHistoryOrUsage",
        "redactionRequiresBothMatchingTombstonesAndIsOneWay",
        "commonInventoryCountsAllStudyRowsAndCiphertextsIncludingCloseReceipt",
        "metadataOnlyCloseCannotExceedActualByteCapacityAndReplayCostsNothing",
        "exactSchemaGateRejectsBothOldAndUnreviewedFutureVersion",
        "clearingOnlyOldSourcesCannotCommitTheConversationTombstone",
        "everyOperationAtMaxMinusTwoPreservesTheFinalDeletionIncrement",
    ),
    "StudyStorageFixtures": (
    ),
    "StudyStorageSqlTest": (
        "eachReceiptIsTheOnlyConversationBumpAndHistoricalInputsRemainValid",
        "unreceiptedSourceSessionAndTimestampChangesRollback",
        "unreceiptedStartCannotCommit",
        "nullPriorVersionCannotBypassAnswerOrCloseChecks",
        "mismatchedInputReceiptAndHistoricalObservationCannotCommit",
        "sourceIdentityTextCommitmentAndReceiptAreImmutable",
        "wrongEpochAndCrossSessionSourceBindingsFail",
        "duplicateQuestionAttemptAndCloseThenAnswerCannotCommit",
        "gapDuplicateTransitionAndBackwardTimestampCannotCommit",
        "conflictDoNothingDoesNotFireConversationBump",
        "aggregateRowCapCountsTheFinalCloseReceiptAndRejectsCapPlusOne",
        "defensiveSessionAndAnswerCapProbesRejectCorruptCapPlusOneWithoutCommitting",
    ),
    "StudyLessonContractTest": (
        "exactTypedWireHasNoModelIdentityAndMintsOnlyAtAdmission",
        "ordinaryAndStudyPurposeCannotSubstituteTheirSuccessKinds",
        "lessonRequiresCurrentContextLocalModeAndNoTools",
        "wrongCountsBlankScalarNulAndOversizeTextFailClosed",
        "unknownAuthorityAndUnboundUsageNeverBecomeAcceptedLessons",
        "storedEnvelopeIsCanonicalVersionedAndRejectsProseOrExtraFields",
        "needInputAndErrorRemainCompatibleForBothPurposes",
        "storedDuplicateQuestionIdsAreRejectedRatherThanBecomingTwoTargets",
    ),
    "StudyLessonHttpTest": (
        "authenticatedExplicitRouteSettlesAndReadsTheExactTypedLesson",
        "missingAuthenticationOriginCsrfAndJsonRejectBeforeReservation",
        "clientAuthorityPurposeSourceAndReceiptFieldsCannotEnterTheRoute",
        "idempotencyHeaderMustBeSingleCanonicalAndEqualToBodyIdentity",
        "queriesBodiesDuplicateJsonAndNoncanonicalTargetsAreClosed",
        "deletionAndRevokedSessionCannotExposeAFormerTypedResult",
    ),
    "StudyLessonLifecycleTest": (
        "reservedPurposeAndTypedOutputFlowThroughRealSettlementAndPlainReaders",
        "ordinaryHashesAndOmittedPurposeRemainByteCompatible",
        "idempotencyCannotSwitchPurposeInEitherDirectionOrResubmitCompletedWork",
        "purposeMismatchAndMalformedOutputKeepUnknownReservedUsage",
        "ordinaryPurposeRejectsLessonWithoutCreatingTypedHistory",
        "needInputAndUnavailableRemainOrdinaryDataAndErrorObservation",
        "pauseAfterDispatchRecordsFactsButNeverMintsOrStoresLesson",
        "logoutAfterDispatchRecordsFactsButRejectsOutputAndTypedRead",
        "contextRotationAfterDispatchRecordsFactsButNeverAcceptsStaleLesson",
        "crossOwnerNonLessonAndCorruptReadsNeverExposeSourceText",
        "acceptedHistoricalLessonSurvivesContextChangeWithoutCreatingAuthority",
        "deletionErasesBothTypedResultAndRenderedTranscriptAndPreventsResurrection",
        "reservationPurposeIsImmutableAndCommittedByDeletionSnapshot",
        "queuedPauseAndLogoutCancelWithoutCallingTheProvider",
        "realSessionRotationAndIdleExpiryDiscardLateTypedResponses",
        "deletingASelectedMemoryInterruptsAndDiscardsLateLessonButKeepsBoundUsage",
        "activeConversationDeletionIsBlockedAndCompletedReplayNeverRemintsQuestionIds",
        "descendantLockWaitRechecksSessionClockBeforeLessonIdsOrContentCommit",
    ),
    "StudyLessonMigrationTest": (
        "v14PreparedAuthorityIsRevokedAndConsumedPreparationRemainsUnchanged",
    ),
    "StudyLessonSqlTest": (
        "directReceiptWritesCannotSubstituteOrdinaryAndStudyPurpose",
        "sharedClarificationAndUnknownPurposeRemainStrictlyConstrained",
    ),
    "StudyAnswerSourcePolicyTest": (
        "sharedSyntheticSourceCasesInvokeActualGuard",
        "otherwiseValidCommandTargetSubstitutionsAreRejected",
        "trustedSelectionTargetCannotBeReplacedOrOmitted",
        "unavailableAndCrossOwnerInputsFailBeforeKernelProjection",
        "sourceMessageConversationAndNullableProjectMustMatchExactly",
        "sourceRevisionBoundsAndChangesFailClosed",
        "onlyStoredUserDirectProvenanceCanSupplyAnAnswer",
        "exactSourceTextCannotBeTrimmedNormalizedOrSubstituted",
        "existingKernelRemainsTheStudyTargetAndVersionAuthority",
        "repeatedAndDuplicateAttemptsNeverOverwriteExistingAnswers",
        "closedAndSourceUnavailableStudyStatesCannotReopen",
        "unavailableErrorsAndSourceDiagnosticsContainNoPrivateValues",
        "acceptedAnswerPreservesIdentityAndInputValuesWithoutSideEffects",
        "existingUnicodeAndTargetBoundsStillApplyAfterSourceBinding",
        "directInputRequiresIndependentlyObservedTargetAndClosedSourceKind",
    ),
    "RestoreLaunchGuardTest": (
        "everyPresentControlValueDeniesWithoutReadingIt",
        "ordinaryUnenrolledAdmissionIsUnchanged",
        "anyAnchorObjectDeniesWithoutOpeningContents",
        "symlinkRuntimeAndInvalidPathsDeny",
        "coreRefusesBeforeSpringAndBootstrapBeforeJdbcOrTty",
        "ordinaryCoreStillCallsTheExistingSpringEntry",
    ),
    "ConversationDeletionServiceTest": (
        "exactPreviewCommitAndPermanentReceiptEraseEverySourceCopy",
        "staleSnapshotAndChangedIdempotencyBodiesNeverRedact",
        "preparationReplayDoesNotExtendExpiryAndOldAuthorityDoesNotRebind",
        "receiptReplayAfterPauseAndAgedReauthIsReadOnly",
        "independentActiveWorkDoesNotBlockTargetDeletion",
        "activeUnknownAndPendingDescendantsRefuseDespiteTerminalParent",
        "needInputWithOnlyClosedDescendantsIsEligible",
        "missingMarkerIsUnsupportedEvenForCancelledBeforeStart",
        "rolledBackAuditFailureRetainsAllSourceAndAuthority",
        "hundredUnconsumedPreparationsAreNotALifetimeDeletionLimit",
        "receiptWrongSessionDeniedButFreshSessionCanReadDeletedState",
        "durableTombstoneSurvivesApplicationRestart",
        "titleAndCiphertextByteCapRefusesBeforeAnyAuthorityIsIssued",
        "runCapPlusOneRefusesWholeScopeWithoutPrefixMutation",
        "revokedSessionAndFutureReauthenticationNeverAuthorizeNewDeletion",
        "graphSnapshotDoesNotIncludeObservationTimeOrLateNumericReceipts",
        "confirmationBooleanHashAndUnknownAuthorityFieldsAreStrict",
        "concurrentDifferentKeyConfirmsConsumeOneAuthorityOnly",
        "authenticationIsRecheckedAfterWaitingForOwnerLock",
        "exactRunCapIsWholeScopeAndNotADeletionPrefix",
    ),
    "ConversationDeletionSqlTest": (
        "dispatchMarkerCannotBeInsertedBackfilledOrChangedAfterDispatch",
        "markedClosedSyntheticGraphRetainsExactV12UsageAndAcceptsOnlyLateNumericReceipt",
        "tombstoneRedactionAndIdentityCannotBeReversedOrPartiallyCommitted",
        "sourceVersionIsMonotonicAcrossChangeAndReversalButNotReadOrPreparation",
        "directCrossConversationMessageJobAndHistoryAnchorsAreRejected",
        "settledContextWithoutReservationIsAnUnverifiedGraph",
    ),
    "ConversationDeletionHttpTest": (
        "stateAndReceiptReadsHaveNoStoreAndRejectEveryQueryOrBody",
        "missingAuthenticationCsrfOriginAndNonJsonFailBeforeService",
        "exactlyOneCanonicalIdempotencyKeyAndNoQueryAreRequired",
        "duplicateKeysMalformedUtf8FloatingValuesAndLoneSurrogatesAreRejected",
        "committedConfirmUsesClosedEnvelopeAndServiceOwnsCommitBoundary",
    ),
    "ConversationDeletionBarriersTest": (
        "deletedTranscriptIsGatedBeforeDecryptReplayAndEveryCompatibilityPathAfterRestart",
        "completedMockAuthorityCannotBeReplayedAndOtherWorkStillClaims",
        "supportedDispatchAloneMintsExactImmutableNoCacheProvenance",
        "requestContextTamperingCannotMintMarkerOrCallTransport",
        "cancelledBeforeDispatchRemainsUnmarkedAndUnsupported",
        "deletingAnotherConversationDoesNotInvalidateActiveDispatchOrEpochs",
        "lateNumericMeasurementCanCommitWithoutSourceOrAuthorityResurrection",
        "historyWaitingBehindDeletionUsesCurrentCommittedStateBeforeReturningContent",
        "historyWaitingBehindLogoutRevalidatesCurrentSessionBeforeAnyContent",
        "contentBarrierRejectsOldRepeatableReadSnapshotAndRevokedSession",
    ),
    "ConversationDeletionFixtures": (
    ),
    "ExportServiceTest": (
        "jobsAreOnlyMetadataAndExactReplayDoesNotRefreshExpiry",
        "exactScopeCurrentValuesCanonicalUnicodeAndClosedOutputFields",
        "currentSnapshotIncludesLaterCreateAndExcludesLaterDeleteWithoutArtifactCopies",
        "ownerSessionEpochReauthAndPauseAreCheckedIndependently",
        "strictRequestRejectsAuthoritySmugglingScopeConfusionAndMalformedIdentifiers",
        "ownerCapAndCreationOnlyRetentionAreBoundedAndReplayDoesNotConsumeCapacity",
        "rowCeilingFailsBeforeDecryptAndByteCeilingFailsWithoutTruncation",
        "byteBudgetCountsUtf8AndAllowsExactBoundary",
        "exactThousandRowsSucceedInCanonicalIdOrderWithOnlyAllowlistedFields",
        "databaseOwnerLockTimeoutIsExplicitAndCannotCreateAJob",
        "metadataSurvivesRestartAndDownloadsAreRebuiltFromCurrentRows",
        "priorDeletionLogoutAndRotationWinAgainstWaitingDownload",
        "generatedSnapshotCannotBeRecalledButLaterDeleteFencesNextDownload",
        "finalReleaseRechecksReauthAndExpiryAndReturnsNoPartialDocument",
        "databaseClockIgnoresApplicationClockSkewAndTimeBudgetFailsClosed",
        "realHttpAuthenticationAndDatabaseProduceOnlyExplicitDownloadThenRejectRotation",
    ),
    "ExportHttpTest": (
        "createBindsExactSessionKeyAndBodyWithMetadataEnvelope",
        "metadataReadsForwardExactlyAndNeverTriggerGeneration",
        "contentIsRawUtf8BoundedAttachmentWithSafeConstantFilename",
        "authenticationOriginCsrfAndMediaTypeFailBeforeService",
        "duplicateAbsentAndNonCanonicalKeysFailBeforeService",
        "everyQueryAndNonemptyReadBodyIsRejectedIncludingChunkedAndEmptySegments",
        "strictJsonRejectsDuplicatesMalformedUnicodeAndOversizedInput",
        "knownAndUnexpectedErrorsContainNoAttachmentOrPartialContent",
    ),
    "UsageServiceTest": (
        "emptySummaryHasClosedShapeUnknownCostAndNoObservedPeak",
        "sixDisjointClassesNeverLabelActiveOrLegacyWorkAsZeroMeasured",
        "erroneousDispatchMarkersFailClosedToUncertain",
        "duplicateMeasurementIsHarmlessAndConflictingMeasurementCannotOverwrite",
        "concurrentIdenticalAndConflictingReceiptsHaveOneImmutableWinner",
        "ledgerRepeatsFullResponseAndSafeIntegerValidation",
        "durableBoundaryBindsOwnerRunRequestReservationManifestModelAndExactRequest",
        "reservationThatNeverStartedCannotAcquireMeasurement",
        "errorZeroPlaceholdersAreUncertainEvenBeforeRunSettlement",
        "lateActualAfterErrorUsesSeparateImmutableSlotAndCountsRequestOnce",
        "measurementCommitSurvivesOuterRollbackAndServiceRestart",
        "originalCreationAttributionSurvivesLateRecordingAndCannotBeMoved",
        "measuredPeakIsMaximumRatherThanSumAndReportedZeroIsPreserved",
        "profileTimezoneChangesDisplayWindowsWithoutChangingQuotaOrRows",
        "localDayMonthBoundariesCoverDstLeapMonthAndHalfOpenMidnight",
        "dayAndMonthUseHalfOpenOriginalRequestTimeAndExcludeFutureRows",
        "aReceiptAfterCheckedTimeIsNotPresentedAsAlreadyObserved",
        "snapshotCannotMixConcurrentReceiptProfileAndSessionChanges",
        "ownerSessionExpiryEpochAndLogoutAreRevalidatedWithinReadSnapshot",
        "readDoesNotMutateAnyTableOrExposePrivateIdentityAndContent",
        "receiptRowsRejectUpdateAndDeleteAndForeignBindingAtDatabaseBoundary",
        "corruptProfilesAndDatabaseFailureDoNotMasqueradeAsEmptyUsage",
        "aggregateNumbersFailClosedOutsideSafeIntegerRange",
        "migrationMarksHistoricalReservationsUnavailableAndNewAdmissionsVersionOne",
        "foreignOwnerReservationsCannotEnterSummaryOrForgeReceiptBinding",
    ),
    "UsageLifecycleTest": (
        "cancellationDuringReceiptPersistenceCannotInterruptValidatedAccounting",
        "normalAnswerPersistsMeasuredUsageBeforeAcceptedReply",
        "clarificationIsMeasuredWithoutIssuingAuthority",
        "errorZerosRemainConservativeUncertaintyRatherThanMeasuredZero",
        "invalidResponseDoesNotPersistCountersOrReleaseReservation",
        "transportFailureRetainsUnknownAcrossRestartWithoutRetry",
        "lateAnswerAfterPauseIsRecordedWithoutRunOutputOrAuthorityRevival",
        "lateAnswerAfterLogoutIsRetainedButRevokedSessionCannotReadUsage",
        "lateAnswerAfterContextDeletionIsRetainedWithoutReenteringHistory",
        "cancellationBeforeDispatchHasNoMeasurementAndReleasesOnlyUnstartedCeiling",
        "resultSettlementRollbackCannotEraseAlreadyCommittedActualCounters",
        "receiptFailureKeepsConservativeUnknownRatherThanReturningUnaccountedSuccess",
        "staleStartedRecoveryRemainsUnknownAndIsNeverAutomaticallyResubmitted",
        "identicalMessageAdmissionDoesNotReserveOrMeasureTwice",
    ),
    "UsageHttpTest": (
        "authenticatedSummaryUsesExistingEnvelopeNoStoreAndExplicitEmptyUnknowns",
        "everyQueryParameterIncludingOwnerDatesDuplicatesAndEmptyValuesIsRejected",
        "everyNonemptyGetBodyIsRejectedWithoutReflectingPrivateContent",
        "missingExpiredRevokedAndOldEpochSessionCannotReadUsage",
        "pausedOwnerCanReadWithoutChangingBusinessTablesOrAuthority",
        "databaseFailureReturnsSanitizedFailureRatherThanZeroUsage",
        "corruptProfileDoesNotBecomeUtcEmptyUsage",
    ),
    "ExecutionControlServiceTest": (
        "pauseWinningSettlementRaceFencesAlreadyStartedEffectAndPreservesUnknown",
        "readHasOneDatabaseCheckedTimeAndClosedPrivateProjection",
        "actualTransitionsAdvanceOnceAndRetainOnlyInitiatingSession",
        "exactResponseLossResubmissionCannotAdvanceEpochAgain",
        "sameStateAtCurrentVersionIsNoOpButStaleVersionStillConflicts",
        "duplicateConcurrentSnapshotHasExactlyOneTransition",
        "profileUpdateMakesDisplayedControlVersionStale",
        "freshReadDoesNotProveAnOlderAmbiguousPauseSucceeded",
        "recentAuthenticationComesFromDatabaseAndReadNeedsOnlyLiveSession",
        "sessionExpiryRevocationAndForgedOwnerFailClosed",
        "queuedWorkAndPendingApprovalNeverReviveAfterResume",
        "pauseFencesClaimedButUnstartedLeaseWithZeroEffects",
        "auditFailureRollsBackOwnerFenceAndQueuedInvalidation",
        "ownerVersionCeilingAllowsNoOpAndRejectsTransition",
        "strictPayloadRejectsMissingSpoofedAndUnsafeValues",
        "capturedHttpMessageAdmissionCannotWaitAcrossPauseAndResume",
        "blockedTransitionRechecksDatabaseReauthenticationAfterOwnerLockWait",
        "pauseWinningErrorSettlementRaceAlsoPreservesUnknown",
        "securityEpochCeilingRejectsTransitionWithoutChangingState",
        "localInFlightRemainsUnknownQueuedReservationIsCancelledAndLateAnswerIsDiscarded",
        "taskAndMemoryIntentsFromBeforePauseCannotBeConsumedAfterResume",
        "scheduleAuthorityNeverRevivesAcrossPauseAndResume",
        "settlementCannotUseTransactionStartTimeToAcceptAnExpiredLeaseAfterLockWait",
        "leaseExpiringAfterCurrentCheckRollsBackEarlierActionSettlement",
    ),
    "ExecutionControlHttpIntegrationTest": (
        "authoritativeStatusIsPrivateDatabaseCheckedAndRequiresAuthentication",
        "freshCasTransitionResponseLossRetryAndSameStateNeverDoubleIncrement",
        "originCsrfRecentReauthAndStrictBodiesCannotBeBypassedByEmergencyControls",
        "statusRejectsOwnerSpoofQueryAndAnyBody",
        "initiatingCookieSurvivesButOtherSessionCannotReadOrResume",
        "pausePreventsQueuedWorkAndResumeOnlyPermitsNewWork",
        "unknownRunDoesNotDisableAccountControlsOrBecomeRetriedAfterResume",
        "sharedProfileVersionConflictsWithoutLeakingCurrentProfileOrSecurityEpoch",
    ),
    "ExecutionControlHttpTest": (
        "committedPauseInterruptsOwnerExactlyOnceAfterTransition",
        "alreadyPausedNoOpDoesNotAttemptProcessInterruption",
        "resumeDoesNotAttemptProcessInterruption",
        "stalePauseDoesNotAttemptProcessInterruption",
        "statusDelegatesLiveSessionWithoutWorkerSideEffects",
    ),
    "AuditHistoryServiceTest": (
        "emptyPageIsClosedAndUsesOneDatabaseObservationTime",
        "everyKnownEventHasAnExplicitKindAndOnlyRunEventsHydrateRuns",
        "unknownEventsAreSanitizedWithoutPrefixClassification",
        "currentRunStateIsExplicitAndUnknownIsNeverConvertedToSuccess",
        "missingRunKeepsOnlyHistoricalReferenceAndCannotBecomeADeadLink",
        "otherOwnerEventsAndCurrentStateCannotLeakThroughPolymorphicReferences",
        "secretsContentAndAuthenticationHashesNeverEnterProjectionOrCursor",
        "descendingKeysetHandlesTimestampTiesAndExactPageEndsWithoutDuplicates",
        "concurrentNewerRowsDoNotRepeatExistingRowsAndBackdatedRowsCanAppearOnFreshPages",
        "cursorRejectsTamperScopeSessionAndEpochReuse",
        "cursorCannotReplayIntoAnotherLiveOwnerSession",
        "concurrentEqualTimestampInsertsUseTheStrictUuidBoundary",
        "malformedStoredRunStateFailsClosedWithoutReturningTheRawValue",
        "sessionAndOwnerAreRevalidatedBeforeCursorAccess",
        "invalidScopeAndPageBoundsFailClosed",
        "everyTableIncludingSessionsAuditAndNoncesRemainsByteEquivalentWhilePaused",
        "concurrentCommitCannotSplitEventAndCurrentStateWithinOneReadOnlyPage",
        "malformedStoredTimestampsFailClosedRatherThanProducingEmptyOrSuccessfulData",
        "databaseFailureIsNotTreatedAsAnEmptyHistory",
        "authenticButUnknownCursorShapesAndNoncanonicalBoundariesAreRejected",
        "cursorExpiresAtFifteenMinutesOfDatabaseTimeWithoutRenewalAcrossPages",
    ),
    "AuditHistoryHttpTest": (
        "authenticatedGetUsesExistingEnvelopeAndNoStoreWithClosedEmptyPage",
        "defaultPageSizeAndExplicitRunsScopeAreBounded",
        "unknownDuplicateAndBlankQueryParametersAreRejected",
        "invalidScopeNoncanonicalLimitsAndOversizedQueriesFailClosed",
        "cursorTamperingReturnsSanitizedNonretryableError",
        "missingRevokedIdleExpiredAbsoluteExpiredAndOldEpochSessionsReturn401",
        "everyNonemptyGetBodyIsRejectedRegardlessOfContentType",
        "pausedHistoryOnlyRefreshesMiddlewareLastSeenAndNeverMutatesBusinessTables",
        "databaseFailureReturnsSanitizedErrorRatherThanEmptyHistory",
        "corruptTimestampReturnsSanitized500AndUnknownEventTextNeverLeaks",
    ),
    "TodayServiceTest": (
        "ranksEntireOwnerSetRatherThanFirstPage",
        "orderingExcludesClosedAndUsesPriorityThenDueThenIdWithNullsLast",
        "deadlinesHaveExclusiveLocalMidnightNoLowerBoundExactCountAndTruncation",
        "checkedTimeComesFromDatabaseAndOwnerTimezoneDeterminesDate",
        "timezoneRulesCoverDstShortLongDaysAndExactMidnightUsingPostgresInstants",
        "sessionAndOwnerAreRevalidatedWithoutTouchingIdleExpiry",
        "otherOwnerTasksCannotLeak",
        "allTablesIncludingSessionsAuditAndNoncesRemainByteEquivalent",
        "concurrentCommitCannotSplitTopRowsDeadlineRowsAndTotal",
        "databaseAndCorruptProfileErrorsNeverBecomeAvailableEmptyData",
        "invalidStoredTaskTitlesAndTimestampsFailClosed",
        "invalidStoredProfileFieldsFailClosed",
        "unrepresentableNextDayAndLocalDateFailClosed",
    ),
    "TodayHttpTest": (
        "authenticatedGetHasExistingEnvelopeNoStoreAndHonestEmptySections",
        "noQueryParametersAreAccepted",
        "missingRevokedIdleExpiredAndAbsoluteExpiredSessionsReturn401",
        "databaseFailureReturnsSanitizedErrorNotAnEmptyDashboard",
        "getDoesNotCreateBusinessSideEffectsAndOnlyMiddlewareTouchesSession",
        "nonemptyGetBodiesAreRejectedRegardlessOfContentType",
        "malformedStoredSummaryReturnsSanitized500",
    ),
    "MailDraftServiceTest": (
        "foreignFixtureRestoresSingletonAndDependenciesAfterAssertionFailure",
        "encryptedCreateDetailMetadataAndReceipt",
        "patchPreservesOmittedFieldsAndOriginalReceiptReplaysCurrentRecord",
        "concurrentSameKeyCommitsOneDraft",
        "concurrentDistinctPatchKeysCannotLoseAnUpdate",
        "liveOwnerAndOriginalSessionAreRequiredForReplayAndReconciliation",
        "expiredAndStaleEpochSessionsCannotReadOrWrite",
        "pausedBlocksNewWritesButAllowsReadsReceiptsAndExactReplays",
        "noRecentReauthenticationIsNeeded",
        "projectReferencesRequireActiveOwnerAndListsRetainInactiveHistory",
        "paginationIsStableAndOwnerFilterAndSignatureBound",
        "strictValidationAndMergedRecipientLimit",
        "bodyAndCanonicalByteLimitsAndEmptyContent",
        "versionExhaustionAndAuditFailureRollBackEverything",
        "ciphertextCannotBeSwappedAcrossDrafts",
        "missingReceiptHasExactlyThreeNullMetadataFields",
        "actualPostgresHttpCreatePatchReceiptAndMissingCsrf",
        "frozenCommitmentAndCursorVectorMatches",
        "authenticatedCursorStillRequiresStrictTimestampAndPayloadShape",
        "sharedCreateAndPatchFixturesMatchRuntimeShapeValidation",
    ),
    "MailDraftHttpTest": (
        "missingResourceExceptionHasSanitized404",
        "unexpectedServiceFailureRemainsSanitized500",
        "unmappedReadIsSanitizedWithoutBypassingAuthentication",
        "createAndPatchUseOwnerSessionAndExactBodyWithNoStore",
        "authenticationOriginAndCsrfFailBeforeService",
        "missingDuplicateAndNonCanonicalKeysFail",
        "strictQueryAndPathValidation",
        "strictJsonRejectsDuplicateKeysDecimalsAndMalformedUnicode",
        "metadataReadsAndReceiptNeverMutate",
        "explicitProjectAndMaximumLimitAreForwarded",
        "errorsAreRedactedAndNoSendRouteExists",
    ),
    "ConversationHistoryHttpTest": (
        "authenticatedReadUsesSessionOwnerAndNoStore",
        "noSessionCannotReadHistory",
        "queryBoundsUnknownFieldsAndRepeatedValuesAreRejected",
        "messagePageRetainsNullableCanonicalFields",
        "streamResumeUsesOneExplicitCursorAndPreservesResyncCode",
        "foreignConversationIsNotFoundAndErrorsDoNotDiscloseContent",
    ),
    "ConversationHistoryTest": (
        "latestPageAndStableOlderPagesCoverMoreThanTwoHundredMessages",
        "equalTimestampsRemainStableAndNewMessagesDoNotDisplaceOlderCursor",
        "readsNeverMutateAndForeignScopesStayHidden",
        "durableReplyIsEncryptedIdempotentAndLatestRunIgnoresLateReply",
        "unknownCancellationAndRollbackNeverInventAReply",
        "needInputIsAnAssistantQuestionAndNoJobsAreAdded",
        "replayIsBoundedAndUnavailableCursorRequiresCanonicalResync",
        "cursorExpiryAuthenticationAndRestartAreDeterministic",
        "emptyConversationsAndPausedExecutionRemainReadable",
        "messageByteBudgetPreservesAllPages",
    ),
    "CoreSecurityTest": (
        "bootstrapOnlyOnceAndWrongAttemptsPersist",
        "concurrentBootstrapCreatesOneOwner",
        "passwordHashAndSessionRotation",
        "repeatedLoginFailureRateLimited",
        "strictJsonRejectsDuplicateUnknownFloatTrailingAndMalformedUtf8",
        "canonicalIntegersUnicodeAndTamper",
        "encryptedMessageDedupAndOwnerBoundary",
        "maliciousProposalBlockedWithZeroEffects",
        "approvalTamperFailsClosed",
        "concurrentApprovalConsumesOnce",
        "auditFailureRollsBackApprovalAndJob",
        "timeoutUnknownNeverRetriesAndReconcileOnlyReads",
        "pauseAndCancelPreventQueuedEffects",
        "cancelPendingApprovalRevokesIt",
        "expiredLeaseFencesOldGenerationAndNoRetryAfterStart",
        "aadPreventsCrossRecordDecryption",
        "sharedJcsVectorsMatchBytesAndHash",
        "concurrentLoginReservationsNeverExceedFive",
        "compositeOwnerFkRejectsCrossOwnerRelation",
        "originAndCsrfRejectBeforeController",
        "capturedSessionCannotApproveAfterLogout",
        "untrustedProposalCannotChooseAuthorityOrUnregisteredTool",
        "nonceReservationDoesNotBorrowExhaustedBusinessPool",
        "twentyConcurrentMessageAcceptsDoNotStarveNoncePool",
        "nonceReservationSurvivesBusinessRollback",
        "localReservationDedupAndSuccessfulEncryptedResult",
        "localTransportAmbiguityKeepsReservationAndNeverRetries",
        "localQueuedCancelMakesZeroModelCalls",
        "localConcurrentReservationsNeverExceedTwo",
        "localDailyBudgetStopsBeforeModelCall",
    ),
    "InferenceContextServiceTest": (
        "singletonAndTransactionBoundariesRemainEnforced",
        "nullProjectSelectsOnlyGlobalAndPersistsNoPlaintext",
        "overridesAndTombstonesAreAppliedBeforeCap",
        "causalAnchorExcludesLaterCompletionsAndKeepsAcceptedPairs",
        "duplicateAssistantCannotExistAndResultRefIsNotAnExtraReply",
        "legacyAndOtherConversationHistoryIsNotInjected",
        "authorizationAndInactiveProjectFailBeforeBadCiphertext",
        "rotationExpiryAndRestartRemainRevoked",
        "deletionInvalidatesQueuedAndDropsPriorEpochHistory",
        "startedDeletionRetainsFullUnknownReservationAndRejectsLateSettlement",
        "mutationRollbackRestoresEpochAndContext",
        "revisedSourceOrProjectVersionFailsIndependentMetadataGate",
        "settledInvalidatedContextCannotReenterHistory",
        "sessionRevocationStopsActiveContextButRetainsAcceptedHistory",
        "targetedInvalidationDoesNotAdvanceOwnerEpochOrCancelOtherRuns",
        "logoutBlocksSelectionBeforeSourceDecryption",
        "profileTimezoneIsReadThroughEncryptedOwnerSettings",
        "malformedProfileTimezoneFailsClosed",
        "oversizedHistoryFailsClosedWithoutPartialMetadata",
        "digestIsOwnerBoundAndNotPublicContentHash",
        "ownerLockSerializesDeletionAgainstSelection",
    ),
    "LocalContextLifecycleTest": (
        "realDispatchTransportsCurrentMemoryProfileAndPersistedAcceptedPair",
        "publicAcceptRejectsEveryCallerSuppliedContextFieldBeforeReservation",
        "queuedLogoutCancelsWithoutModelCall",
        "queuedMemoryRevisionCancelsWithoutModelCall",
        "inFlightDeletionInterruptsAndDiscardsLateSuccessfulReply",
        "inFlightSessionRotationDiscardsLateSuccessfulReply",
        "inFlightProfileMutationDiscardsLateSuccessfulReply",
        "firstProfileMutationAlsoRevokesDefaultTimezoneDispatch",
        "failedDeletionAuditRollsBackEpochContextAndQueuedReservation",
        "failedProfileAuditRollsBackContextAndRunState",
        "requestHashesAreOwnerHmacAndNoContextPlaintextSnapshotIsStored",
        "idempotentReplayRequiresCurrentLiveSessionButNotModelAvailability",
        "inFlightIdleSessionExpiryDiscardsLateSuccessfulReply",
        "acceptedHistoryRemainsAvailableAfterLogoutAndNextLogin",
        "malformedSourceCiphertextBlocksBeforeModelAndCannotRetry",
        "oversizedPersistedHistoryBlocksBeforeModelAndCannotRetry",
        "queuedIdleSessionExpiryCancelsWithoutModelCall",
        "legacyShaReplayReturnsOnlyExistingStatusWithoutEnqueueOrContextPreparation",
        "legacyShaReplayRejectsChangedBody",
        "newEligibleRunCannotUseLegacyPlainShaFallback",
        "legacyShaFallbackRequiresExistingInferenceReservation",
        "legacyShaReplayStillRequiresLiveSessionAndMatchingConversation",
    ),
    "LocalInferenceContractTest": (
        "signatureBindsExactCanonicalBodyAndOwner",
        "localConfigurationFailsClosedWithoutSecrets",
        "bodySubscriberRejectsOverLimitWithoutWaitingForComplete",
        "responseCannotMintToolsAuthorityOrUsage",
        "responseMustMatchReservationAndManifest",
        "validClarificationIsDataNotUnknownOrAuthority",
    ),
    "LocalInferenceTransportTest": (
        "realMtlsSignedBodyAndFullResponseDeadline",
    ),
    "MemoryServiceTest": (
        "createReadEncryptedProvenanceAndMetadataReceipts",
        "exactArgumentsOneUseAndNoGeneralTaskAuthority",
        "revisePreservesEncryptedHistoryAndStaleVersionFails",
        "deletePurgesAllContentAndOldIntentReplayWithoutLosingMetadata",
        "revivalNeedsFreshExplicitSaveAndNeverRestoresPriorGeneration",
        "projectOverridesAndTombstonesMaskGlobalBeforeLimit",
        "ownerProjectFiltersPrecedeDecryption",
        "revokedEpochAndSessionCannotReadOrConsume",
        "reauthenticationMustBeCurrentDatabaseStateAndSameSession",
        "changedVersionInvalidatesDeletePreviewAndConfirm",
        "expiredAndTamperedAuthorityCannotMutate",
        "pausedOwnerBlocksNewAuthorityAndDeleteButAllowsReadsAndReceipt",
        "deletionAuditFailureRollsBackPurgeApprovalAndLedger",
        "restartDoesNotReviveDeletedDataOrReplays",
        "concurrentSameKeyHasExactlyOneLogicalMutation",
        "deleteVersusReviseRaceHasOneWinnerWithoutResurrection",
        "strictUnicodeLimitsAndSafeIntegers",
        "signedKeysetPagesCannotCrossOwnerScopeOrResource",
        "authorityCommitmentsAreEncryptedAndPurged",
        "oldWriteReceiptRemainsRedactedAfterExplicitRevival",
        "ownerLockSerializesRotationAndLogoutBeforeDeleteConfirmation",
        "sharedCanonicalHashVectorsMatchBothAuthorityBuilders",
        "explicitUnicodeWhitespaceMatchesBothTextFieldsAndPreservesNonblankControls",
        "databaseClockControlsReauthExpiryAndTimestampsDespiteApplicationClockSkew",
    ),
    "MockHttpIntegrationTest": (
        "authenticationCsrfOriginAndSessionRevocation",
        "strictBindingDeduplicationAndMockCompletion",
        "approvalPayloadVersionAndSingleConsumption",
        "cancellationRevokesPendingApprovalAndQueuedEffect",
        "unknownDoesNotRetryAndReconciliationOnlyReadsExistingEffect",
        "eventCursorBoundToConversationAndUnknownRunDenied",
    ),
    "OwnerProfileHttpTest": (
        "explicitPatchPassesServerSessionAndExactBody",
        "authenticationOriginAndCsrfRejectBeforeSettings",
        "strictQueryAndIdempotencyHeaderValidation",
        "receiptReadNeverReplaysPatch",
        "conflictReturnsVersionWithoutProfileValues",
        "actualMeControllerDelegatesReadAndRejectsUnknownQuery",
    ),
    "OwnerProfileServiceTest": (
        "legacyDefaultsAreProjectionWithoutWritesOrInferredName",
        "encryptedPatchRoundTripAndOnlySubmittedFieldsChange",
        "nullNameClearsWhileOmittedFieldsRemain",
        "sameKeyReplayUsesCurrentProfileAndOriginalMetadataOnly",
        "receiptReadIsNonMutatingAndUnknownDoesNotMeanFailed",
        "ordinaryProfileEditRequiresNoRecentReauthAndWorksPaused",
        "pauseResumeShareVersionButProfileDoesNotChangeSecurityEpoch",
        "rotatedSessionCanReconcileButCannotReplayOldWrite",
        "revokedStaleExpiredAndUnknownSessionsFailBeforeDecrypt",
        "strictFieldsTypesBoundsAndSharedUnicodeConvention",
        "exactTimezoneOptionsRejectOffsetsAndAbbreviations",
        "safeVersionExhaustionIsFailClosed",
        "auditFailureRollsBackProfileVersionAndReceipt",
        "restartRetainsEncryptedProfileAndReceipt",
        "concurrentSameKeyCommitsExactlyOnce",
        "concurrentDifferentKeysSameVersionHaveOneWinner",
        "ownerLockSerializesLogoutBeforePendingPatch",
        "profileTimestampsUseDatabaseClockUnderApplicationSkew",
        "knownInstantsDisplayDstOffsetsWithoutCreatingLocalSchedules",
        "sharedPatchFixturesAgreeWithServerValidation",
        "ownerLockSerializesRotationAndPauseBeforePendingPatch",
        "integratedGetMeUsesPersistedProfileAndStrictQuery",
    ),
    "ProjectHttpTest": (
        "explicitCreateUsesServerSessionAndExactBody",
        "unauthenticatedAndWrongOriginOrCsrfCannotCreate",
        "missingDuplicateAndNonCanonicalCreateKeysAreRejected",
        "listAndGetAreReadOnlyAndStrict",
        "uncertaintyReceiptNeverTriggersMutation",
        "errorsAreRedactedAndUnknownResourceIs404",
    ),
    "ProjectServiceTest": (
        "createReadReceiptAndEncryption",
        "replayConflictAndDuplicateDisplayNames",
        "concurrentSameKeyCreatesExactlyOne",
        "pausedAndRevokedSessionsCannotCreateButReadsAndExistingReplayWork",
        "staleEpochAndExpiredSessionFailBeforeMutation",
        "ownerFiltersApplyBeforeDecryptionAndReceipts",
        "stableKeysetPagingHandlesEqualTimestampsAndScopedTamper",
        "legacyProjectsReadWithEmptyDescriptionAndMalformedInputsFail",
        "auditFailureRollsBackProjectAndReceipt",
        "sharedCreateFixturesMatchRuntimeValidation",
        "descriptionHeavyPagesStayWithinClientBudgetWithoutDroppingRecords",
        "expiredSignedCursorRequiresReadOnlyRefresh",
    ),
    "TaskSecurityTest": (
        "canonicalIntentMatchesSharedFixture",
        "createsEncryptedTaskAndNeverCreatesRunOrGenericAuthority",
        "issuanceAndMutationHaveIndependentExactIdempotencyAndOriginalSnapshots",
        "exactBindingRejectsContentTargetAndToolTampering",
        "concurrentIdenticalCreateCommitsExactlyOnce",
        "concurrentDifferentKeysCannotReuseIntent",
        "twoDevicesConflictReturnsOwnedLatestAndDoesNotConsumeLosingIntent",
        "explicitCompleteCancelAndReopenRemainIndependentOfRuns",
        "foreignOwnerSessionExpiryLogoutAndEpochFailClosed",
        "pauseRevokesPreviouslyIssuedAuthorityEvenAfterResume",
        "auditFailureRollsBackTaskIntentConsumptionAndReceipt",
        "absoluteAndIdleSessionExpiryPreventWritesAndReceiptDisclosure",
        "mutationReceiptCannotCrossSessionsOrSecurityEpoch",
        "updateAuditFailureRollsBackVersionDescriptionIntentAndReceipt",
        "strictFieldsBoundsAndExistingProjectOnly",
        "unicodeBlankTitleAndVersionExhaustionMatchSharedContract",
        "paginatedReadsAndDueFiltersAreBoundedAndDoNotMutate",
    ),
    "NotificationHttpTest": (
        "getAndListOnlyInvokeReadMethods",
        "explicitOpenIsCsrfProtectedWithoutIdempotencyHeader",
        "openWithoutOriginOrCsrfCannotReachService",
        "inboxQueryLimitsAndUnknownFieldsAreRejected",
        "foreignItemErrorsDoNotEchoContentOrIdentifier",
    ),
    "NotificationServiceTest": (
        "getAndListNeverMarkOpenedAndExplicitOpenIsIdempotent",
        "ownerIsolationAndOpenBodyCannotIntroduceAuthority",
        "keysetPagingHasNoDuplicatesAndCursorScopeIsBound",
        "projectFilterCannotReadAnotherProjectAndForeignProjectIsNotFound",
        "openAuditFailureRollsBackAcknowledgement",
        "notificationAndOccurrenceReadViewsHaveOnlyFrozenFields",
    ),
    "ReminderRecurrenceTest": (
        "seoulConvertsExplicitLocalRuleToUtc",
        "gapUsesNextValidInstantRatherThanShiftByGap",
        "foldUsesFirstInstantOnceThenOriginalNextDayRule",
        "gapDoesNotPermanentlyChangeDailyWallTime",
        "nonHourGapResolvesToTransitionBoundary",
        "skippedWholeDateDeduplicatesTheSameInstant",
        "weekdaysSkipWeekendAcrossYearBoundary",
        "weeklyAndCustomDaysUseIsoWeekdays",
        "leapDayAndOnceTerminationAreExact",
        "expiryIsExclusiveAndAncientStartIsBounded",
        "graceChecksFullPrecisionAt3599And3600And3601",
        "skippedLatenessUsesCeilingOnlyAfterGrace",
        "sharedRecurrenceFixtureMatchesEveryResolvedSlot",
        "sharedCanonicalCommitmentsUseDomainSeparatedPrivateKeyCodec",
    ),
    "ReminderTestSupport": (
    ),
    "ScheduleHttpTest": (
        "prepareHasNoKeyAndCreatesOnlyPreviewRouteCall",
        "confirmUsesServerSessionTargetAndExactIdempotencyKey",
        "authenticationOriginAndCsrfRejectBeforeService",
        "missingRepeatedAndMalformedKeysAreRejected",
        "unknownRepeatedAndUnsafeQueryParametersReject",
        "receiptGetCannotReplayAWrite",
        "conflictReturnsOnlyCurrentVersionMetadata",
        "duplicateKeysAndNonIntegerJsonNeverReachService",
    ),
    "ScheduleServiceTest": (
        "inertPreviewConfirmReloadReplaceDisableAndReenable",
        "exactReplayReturnsOnlyOriginalMetadataAndChangedBodyConflicts",
        "rotatedSessionReadsReceiptButCannotReplayOrConsumePreparation",
        "unknownReceiptIsReadOnlyAndInconclusive",
        "recentReauthComesFromLiveDatabaseRatherThanSessionObject",
        "revokedIdleAndAbsoluteExpiredSessionsCannotActivate",
        "previewHashTamperConsumptionAndExpirationFailClosed",
        "passedFirstPreviewRequiresNewReviewInsteadOfSilentRecompute",
        "strictDefinitionRejectsAuthorityFieldsControlsOffsetsAndInconsistentDays",
        "expiryBoundsEmptyRecurrenceAndTimestampPrecisionAreStrict",
        "confirmationCreatesOnlyNarrowInboxAuthority",
        "disabledWhilePausedWithoutReauthAndResumeDoesNotReauthorize",
        "encryptedRowsAndPurposeBindingsHideContentFromMetadata",
        "auditFailureRollsBackActivationAndPreparationConsumption",
        "restartRetainsDefinitionAndMetadataReceipt",
        "parallelConfirmationConsumesPreparationOnce",
        "parallelReplacementsAtOneVersionHaveOneWinner",
        "ownerLockSerializesLogoutBeforeConfirmation",
        "foreignIdsAndCursorContextsNeverReturnPrivateRecords",
        "versionCapFailsWithoutRevokingAuthority",
        "jvmClockSkewCannotChangeDatabasePreviewOrSessionValidity",
        "sharedDefinitionFixturesAgreeWithServerValidation",
        "immutablePreparationMetadataMustMatchEncryptedPreview",
    ),
    "ScheduleWorkerTest": (
        "notYetDueCreatesNoOccurrenceOrJob",
        "repeatedAndParallelPlannersCreateOneUniqueOccurrence",
        "twoWorkersAndRepeatedSettlementCreateOneInboxRecord",
        "expiredLeaseTakeoverFencesOldGeneration",
        "expiredLeaseCannotSettleWithoutTakeover",
        "operationalLeaseRetriesAreBounded",
        "lateness3599And3600DeliverWhile3601Skips",
        "fractionalGraceOverflowIsSkippedWithCeiling",
        "multipleMissedDailySlotsNeverProduceCatchupStorm",
        "authorizationExpiryAndQuotaOverrideGrace",
        "restartAfterPlanningResumesOnlyPersistedPureDatabaseWork",
        "restartAfterCommitRetainsEncryptedInboxWithoutDuplicating",
        "plannerFailureRollsBackOccurrenceJobAndNextTime",
        "deliveryAuditFailureRollsBackInboxCounterAndSettlement",
        "pauseBeforeDeliveryCommitsPreventsEffectUnderOwnerLock",
        "committedDeliverySurvivesLaterPauseAndDisable",
        "disableAndReplacementFenceOldPendingLeases",
        "resumedOwnerCannotReviveOldEpochSchedule",
        "logoutDoesNotRevokeConfirmedBoundedInboxAuthority",
        "encryptedNotificationCopiesSurviveScheduleReplacementAndAadSwapFails",
        "historicalInactiveSchedulesDoNotStarveCurrentDueWork",
        "compositeDatabaseReferencesRejectCrossScheduleOccurrenceBinding",
        "profileTimezoneEditCannotRetimeSavedReminder",
        "sharedApiaCatchupVectorQueuesLatestOnlyAndRetainsEarlierMetadata",
        "newEligibleSlotFencesEarlierAlreadyLeasedWholeDateGapOccurrence",
        "settlementCancelsSupersededSlotEvenBeforeNextPlanning",
        "boundedPlannerMakesProgressPastOneHundredStaleDueRows",
    ),
    "StudySessionPolicyTest": (
        'actualPolicyMatchesEverySharedSyntheticSnapshot',
        'bothAnswersAndFeedbackAreRequiredForReview',
        'noAnswerCannotReceiveFeedback',
        'wrongSessionQuestionOrAttemptCannotAttach',
        'staleVersionAndDuplicatesNeverOverwrite',
        'closingDoesNotRequireLearningOrAnswersAndPreventsLateWrites',
        'unavailableProjectionDropsContentAndNeverReopens',
        'identityIsPreservedAndCollectionsAreDefensivelyCopied',
        'versionsAreBoundedAndExhaustionCannotWrap',
        'unicodeBoundsAndBlankOrMalformedTextAreRejected',
        'invalidRecordGraphsCannotBeConstructed',
        'clarificationIsFeedbackNotMasteryOrAnAutomaticRetry',
    ),
}


class ReportError(Exception):
    """Only fixed internal codes may leave the reporter."""


def fail(code):
    raise ReportError(code)


def regular_bytes(path, root, limit=MAX_FILE_BYTES):
    """Reject symlinked ancestors, special files, and unbounded input."""
    try:
        relative = path.relative_to(root)
        cursor = root
        for component in relative.parts:
            cursor = cursor / component
            if cursor.is_symlink():
                fail("UNSAFE_INPUT")
        metadata = path.stat()
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > limit:
            fail("INPUT_SIZE_OR_TYPE")
        with path.open("rb") as stream:
            data = stream.read(limit + 1)
        if len(data) > limit:
            fail("INPUT_SIZE_OR_TYPE")
        return data
    except (OSError, ValueError):
        fail("INPUT_UNAVAILABLE")


def context_from_env(environ):
    context = {key: environ.get(value, "") for key, value in CONTEXT_ENV.items()}
    if not re.fullmatch(r"[0-9a-f]{40}", context["checkout_sha"]):
        fail("INVALID_CONTEXT")
    if context["pr_head_sha"] and not re.fullmatch(r"[0-9a-f]{40}", context["pr_head_sha"]):
        fail("INVALID_CONTEXT")
    for key in ("run_id", "run_attempt"):
        if not re.fullmatch(r"[1-9][0-9]{0,19}", context[key]):
            fail("INVALID_CONTEXT")
    context["pr_head_sha"] = context["pr_head_sha"] or None
    return context


def tree_files(root, relative):
    base = root / relative
    if not base.is_dir() or base.is_symlink():
        fail("SOURCE_UNAVAILABLE")
    files = []
    for path in base.rglob("*"):
        if path.is_symlink():
            fail("UNSAFE_INPUT")
        if path.is_file():
            files.append(path)
        if len(files) > 2000:
            fail("SOURCE_SIZE_LIMIT")
    if not files:
        fail("SOURCE_UNAVAILABLE")
    return sorted(files)


def hash_files(root, paths):
    digest = hashlib.sha256()
    total = 0
    for path in sorted(paths):
        data = regular_bytes(path, root)
        total += len(data)
        if total > MAX_SOURCE_BYTES:
            fail("SOURCE_SIZE_LIMIT")
        name = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(name).to_bytes(8, "big") + name)
        digest.update(len(data).to_bytes(8, "big") + data)
    return digest.hexdigest()


def source_snapshot(root):
    test_files = tree_files(root, TEST_ROOT)
    java_files = [p for p in test_files if p.suffix == ".java"]
    expected = {root / TEST_ROOT / "dev/myjarvis/core" / (name + ".java")
                for name in ALLOWED_TESTS}
    if set(java_files) != expected:
        fail("TEST_INVENTORY_REVIEW_REQUIRED")
    class_hashes = {}
    for path in java_files:
        data = regular_bytes(path, root)
        try:
            source = data.decode("utf-8")
        except UnicodeError:
            fail("SOURCE_ENCODING")
        # Only a bounded inventory guard, not a Java parser or test runner.
        source = re.sub(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                        " ", source, flags=re.S)
        if re.search(r"@(?:ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Nested|DisplayName|DisplayNameGeneration)\b", source):
            fail("TEST_INVENTORY_REVIEW_REQUIRED")
        if re.findall(r"\bpackage\s+([\w.]+)\s*;", source) != [PACKAGE]:
            fail("TEST_INVENTORY_REVIEW_REQUIRED")
        # Bind literal methods to their reviewed top-level class, not merely a
        # matching filename. Nested/helper tests cannot borrow its XML identity.
        depth = 0
        declarations = []
        for token in re.finditer(r"[{}]|\b(class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)|@Test\b", source):
            if token.group() == "{":
                depth += 1
            elif token.group() == "}":
                depth -= 1
                if depth < 0:
                    fail("TEST_INVENTORY_REVIEW_REQUIRED")
            elif token.group() == "@Test":
                if depth != 1:
                    fail("TEST_INVENTORY_REVIEW_REQUIRED")
            elif depth == 0:
                declarations.append((token.group(1), token.group(2)))
        if depth != 0 or declarations != [("class", path.stem)]:
            fail("TEST_INVENTORY_REVIEW_REQUIRED")
        names = re.findall(r"@Test\b\s+(?:public\s+)?void\s+([A-Za-z][A-Za-z0-9_]*)\s*\(\s*\)", source)
        if (len(re.findall(r"@Test\b", source)) != len(names)
                or Counter(names) != Counter(ALLOWED_TESTS[path.stem])):
            fail("TEST_INVENTORY_REVIEW_REQUIRED")
        class_hashes[PACKAGE + "." + path.stem] = hashlib.sha256(data).hexdigest()
    build_files = [root / name for name in (
        "build.gradle", "settings.gradle", "apps/core/build.gradle", "gradlew",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")]
    # Include dependency locks/verification if introduced; never inspect caches.
    for directory in (root, root / "apps/core"):
        build_files.extend(directory.glob("*.lockfile"))
    build_files.extend((root / "gradle").glob("verification-*.xml"))
    return {
        "source_hashes": {
            "core_main_tree_sha256": hash_files(root, tree_files(root, "apps/core/src/main")),
            "core_test_tree_sha256": hash_files(root, test_files),
            "contract_fixtures_tree_sha256": hash_files(root, tree_files(root, "packages/contracts/fixtures")),
            "build_inputs_sha256": hash_files(root, build_files),
            "verification_harness_sha256": hash_files(root, [root / p for p in (
                "scripts/check.sh", "scripts/java_test_report.py", "scripts/test_java_test_report.py",
                ".github/workflows/m0.yml")]),
        },
        "class_source_sha256": class_hashes,
    }


def report_files(root):
    base = root / REPORT_ROOT
    cursor = root
    for component in Path(REPORT_ROOT).parts:
        cursor = cursor / component
        if cursor.is_symlink():
            fail("UNSAFE_INPUT")
    if not base.exists():
        return []
    if not base.is_dir():
        fail("UNSAFE_INPUT")
    paths = sorted(base.glob("*.xml"))
    if len(paths) > MAX_FILES:
        fail("REPORT_SIZE_LIMIT")
    return paths


def prepare(root, state_path, context):
    if report_files(root):
        fail("PREEXISTING_JUNIT_REPORTS")
    state = {"schema_version": 1, "context": context, "prepared_at_ns": time.time_ns(),
             **source_snapshot(root)}
    # Refuse reuse of state from another run; no cleanup or deletion of inputs.
    try:
        descriptor = os.open(state_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(state, output, separators=(",", ":"), sort_keys=True)
    except OSError:
        fail("STATE_WRITE_UNAVAILABLE")


class SuiteParser:
    """Bounded Expat parser that stores only names awaiting exact allowlisting."""

    CHILDREN = {
        None: {"testsuite"},
        "testsuite": {"testcase", "properties", "system-out", "system-err"},
        "properties": {"property"},
        "testcase": {"failure", "error", "skipped", "system-out", "system-err",
                     "flakyFailure", "flakyError", "rerunFailure", "rerunError"},
    }
    ATTRS = {
        "testsuite": {"name", "tests", "skipped", "failures", "errors", "time", "timestamp", "hostname"},
        "testcase": {"name", "classname", "time"},
        "properties": set(), "property": {"name", "value"},
        "failure": {"message", "type"}, "error": {"message", "type"},
        "skipped": {"message", "type"}, "system-out": set(), "system-err": set(),
        "flakyFailure": {"message", "type"}, "flakyError": {"message", "type"},
        "rerunFailure": {"message", "type"}, "rerunError": {"message", "type"},
    }
    RETRIES = {"flakyFailure", "flakyError", "rerunFailure", "rerunError"}

    def __init__(self):
        self.stack = []
        self.cases = []
        self.counts = Counter()
        self.expected = None
        self.current = None
        self.nodes = 0

    def start(self, tag, attrs):
        self.nodes += 1
        if self.nodes > MAX_CASES * 8 or len(self.stack) >= MAX_DEPTH:
            fail("XML_SIZE_LIMIT")
        parent = self.stack[-1] if self.stack else None
        if tag not in self.CHILDREN.get(parent, set()) or set(attrs) - self.ATTRS[tag]:
            fail("UNSUPPORTED_JUNIT_SCHEMA")
        if sum(len(key) + len(value) for key, value in attrs.items()) > 65536:
            fail("XML_SIZE_LIMIT")
        self.stack.append(tag)
        if tag == "testsuite":
            self.expected = {}
            for key in ("tests", "failures", "errors", "skipped"):
                value = attrs.get(key, "")
                if not re.fullmatch(r"0|[1-9][0-9]{0,4}", value) or int(value) > MAX_CASES:
                    fail("INVALID_SUITE_COUNTS")
                self.expected[key] = int(value)
        elif tag == "testcase":
            if not attrs.get("name") or not attrs.get("classname"):
                fail("INVALID_TESTCASE")
            self.current = {"class": attrs["classname"], "name": attrs["name"],
                            "outcomes": [], "retry_indicators": 0}
        elif parent == "testcase":
            if tag in ("failure", "error", "skipped"):
                self.current["outcomes"].append({"failure": "failed", "error": "errors", "skipped": "skipped"}[tag])
            elif tag in self.RETRIES:
                self.current["retry_indicators"] += 1

    def end(self, tag):
        if tag == "testcase":
            if len(self.current["outcomes"]) > 1:
                fail("AMBIGUOUS_TESTCASE_OUTCOME")
            outcome = next(iter(self.current.pop("outcomes")), "passed")
            self.current["outcome"] = outcome
            self.cases.append(self.current)
            self.counts[outcome] += 1
            if len(self.cases) > MAX_CASES:
                fail("XML_SIZE_LIMIT")
        self.stack.pop()

    def parse(self, data):
        parser = expat.ParserCreate("UTF-8")
        parser.StartElementHandler = self.start
        parser.EndElementHandler = self.end
        def forbidden(*_args):
            fail("FORBIDDEN_XML_DECLARATION")
        parser.StartDoctypeDeclHandler = forbidden
        parser.EntityDeclHandler = forbidden
        parser.ExternalEntityRefHandler = forbidden
        parser.ProcessingInstructionHandler = forbidden
        # Ignore all character data; it may contain secrets, stack traces or paths.
        try:
            data.decode("utf-8")
            if b"\x00" in data:
                fail("INVALID_XML_ENCODING")
            parser.Parse(data, True)
        except (expat.ExpatError, UnicodeError):
            fail("MALFORMED_XML")
        actual = {"tests": len(self.cases), "failures": self.counts["failed"],
                  "errors": self.counts["errors"], "skipped": self.counts["skipped"]}
        if actual != self.expected:
            fail("SUITE_COUNTS_MISMATCH")
        return self.cases


def summarize(cases, class_hashes):
    allowed = {(PACKAGE + "." + cls, method) for cls, names in ALLOWED_TESTS.items() for method in names}
    grouped = {identity: [] for identity in allowed}
    counts = Counter(case["outcome"] for case in cases)
    unmapped = 0
    for case in cases:
        # Only the exact default zero-argument names are accepted. Never trim
        # parameter values, display-name suffixes, paths or arbitrary text.
        name = case["name"]
        identity = (case["class"], name[:-2] if name.endswith("()") else name)
        if identity not in allowed:
            unmapped += 1
        else:
            grouped[identity].append(case)
    methods = []
    for (cls, method), observations in sorted(grouped.items()):
        method_counts = Counter(case["outcome"] for case in observations)
        retries = sum(case["retry_indicators"] for case in observations)
        if not observations:
            outcome = "NOT_OBSERVED"
        elif retries:
            outcome = "RETRY_UNRESOLVED"
        elif len(observations) > 1:
            outcome = "DUPLICATE_UNRESOLVED"
        else:
            outcome = {"passed": "PASS", "failed": "FAIL", "errors": "ERROR", "skipped": "SKIPPED"}[observations[0]["outcome"]]
        methods.append({"class": cls, "method": method, "source_sha256": class_hashes[cls],
                        "outcome": outcome, "observations": len(observations),
                        "counts": {key: method_counts[key] for key in OUTCOMES}, "retry_indicators": retries})
    observed = sum(bool(value) for value in grouped.values())
    return methods, {
        "testcases": len(cases), **{key: counts[key] for key in OUTCOMES},
        "unmapped_testcases": unmapped, "declared_methods": len(allowed),
        "observed_methods": observed, "unobserved_methods": len(allowed) - observed,
        "duplicate_observations": sum(max(0, len(value) - 1) for value in grouped.values()),
        "retry_indicators": sum(case["retry_indicators"] for case in cases),
    }


def empty_report(context, verification_outcome):
    return {"schema_version": 1, "task": ":apps:core:test", "context": context,
            "verification_outcome": verification_outcome, "status": "UNAVAILABLE",
            "evidence": "NONE", "reason_codes": [], "source_hashes": None,
            "report_files_parsed": 0, "counts": None, "methods": []}


def emit(root, state_path, context, verification_outcome):
    report = empty_report(context, verification_outcome)
    try:
        if not state_path.exists():
            fail("PREPARATION_UNAVAILABLE")
        try:
            state = json.loads(regular_bytes(state_path, state_path.parent, 65536))
        except (ValueError, UnicodeError):
            fail("INVALID_PREPARATION")
        if not isinstance(state, dict) or state.get("schema_version") != 1 or state.get("context") != context:
            fail("PREPARATION_CONTEXT_MISMATCH")
        started = state.get("prepared_at_ns")
        if type(started) is not int or not 0 < started <= time.time_ns():
            fail("INVALID_PREPARATION")
        snapshot = source_snapshot(root)
        for key in snapshot:
            if state.get(key) != snapshot[key]:
                fail("SOURCE_CHANGED_DURING_VERIFICATION")
        report["source_hashes"] = snapshot["source_hashes"]
        paths = report_files(root)
        if not paths:
            fail("NO_JUNIT_XML_EXECUTION_UNESTABLISHED")
        cases = []
        total = 0
        for path in paths:
            data = regular_bytes(path, root)
            if path.stat().st_mtime_ns < started:
                fail("STALE_JUNIT_REPORT")
            total += len(data)
            if total > MAX_TOTAL_BYTES:
                fail("REPORT_SIZE_LIMIT")
            cases.extend(SuiteParser().parse(data))
            if len(cases) > MAX_CASES:
                fail("REPORT_SIZE_LIMIT")
        methods, counts = summarize(cases, snapshot["class_source_sha256"])
        report.update(evidence="PARSED_JUNIT_XML", report_files_parsed=len(paths), methods=methods, counts=counts)
        reasons = report["reason_codes"]
        if counts["failed"] or counts["errors"]:
            reasons.append("TEST_FAILURES_OR_ERRORS")
        if not counts["testcases"]:
            reasons.append("NO_TESTCASES")
        for key, reason in (("skipped", "SKIPPED_TESTS"), ("unmapped_testcases", "UNMAPPED_TESTCASES"),
                            ("unobserved_methods", "UNOBSERVED_METHODS"),
                            ("duplicate_observations", "DUPLICATE_OBSERVATIONS"), ("retry_indicators", "RETRY_OUTCOME_UNRESOLVED")):
            if counts[key]:
                reasons.append(reason)
        if verification_outcome != "success":
            reasons.append("VERIFICATION_NOT_SUCCESS")
        report["status"] = "FAIL" if counts["failed"] or counts["errors"] else "INCOMPLETE" if reasons else "PASS"
    except ReportError as error:
        code = str(error)
        report["reason_codes"] = [code]
        report["status"] = "UNAVAILABLE" if code in ("PREPARATION_UNAVAILABLE", "NO_JUNIT_XML_EXECUTION_UNESTABLISHED", "INPUT_UNAVAILABLE", "SOURCE_UNAVAILABLE") else "INVALID"
    return report


def json_line(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def write_report(report):
    # Small bounded lines avoid a single oversized JSON log line being truncated.
    # The trailer makes a truncated/partial stream distinguishable from evidence.
    report_id = hashlib.sha256(json_line(report).encode("utf-8")).hexdigest()
    header = {key: value for key, value in report.items() if key != "methods"}
    header.update(record="java_test_report", report_id=report_id,
                  method_records_expected=len(report["methods"]), complete_requires_end_record=True)
    lines = [json_line(header)]
    for sequence, method in enumerate(report["methods"], 1):
        lines.append(json_line({"schema_version": 1, "record": "java_test_method",
                                "report_id": report_id, "sequence": sequence, **method}))
    digest = hashlib.sha256(("\n".join(lines) + "\n").encode("utf-8")).hexdigest()
    lines.append(json_line({"schema_version": 1, "record": "java_test_report_end", "report_id": report_id,
                           "method_records_emitted": len(report["methods"]), "content_sha256": digest}))
    if any(len(line.encode("utf-8")) > 8192 for line in lines):
        fail("OUTPUT_SIZE_LIMIT")
    print("\n".join(lines), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "emit"))
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--verification-outcome", choices=("success", "failure", "cancelled", "skipped", "unknown"), default="unknown")
    args = parser.parse_args(argv)
    context = None
    try:
        context = context_from_env(os.environ)
        if args.command == "prepare":
            prepare(ROOT, args.state, context)
            return 0
        report = emit(ROOT, args.state, context, args.verification_outcome)
    except Exception as error:
        # Do not print exception messages: errors can contain XML or private paths.
        report = empty_report(context, args.verification_outcome)
        report["status"] = "INVALID"
        report["reason_codes"] = [str(error) if isinstance(error, ReportError) else "INTERNAL_REPORT_ERROR"]
    write_report(report)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
