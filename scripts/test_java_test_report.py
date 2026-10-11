"""Synthetic reporter fixtures only; these tests do not run Java methods."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from xml.sax.saxutils import quoteattr

import java_test_report as report

CONTEXT = {"checkout_sha": "a" * 40, "pr_head_sha": "b" * 40, "run_id": "123", "run_attempt": "2"}
ENVIRONMENT = {value: CONTEXT[key] or "" for key, value in report.CONTEXT_ENV.items()}
CLASS = "dev.myjarvis.core.ConversationHistoryHttpTest"
METHOD = report.ALLOWED_TESTS["ConversationHistoryHttpTest"][0]
MARKER = "SYNTHETIC_PRIVATE_PAYLOAD /private/synthetic/key token=synthetic-secret"
DURABLE_CLASS_COUNTS = {
    'DurableStudyConcurrencyTest': 9,
    'DurableStudyFixtures': 0,
    'DurableStudyHttpTest': 6,
    'DurableStudyMigrationTest': 4,
    'DurableStudyServiceTest': 10,
    'StudyDeletionIntegrationTest': 7,
    'StudyStorageFixtures': 0,
    'StudyStorageSqlTest': 12,
}
CONTROL_CLASS_COUNTS = {'InferenceControlResultTest': 7, 'InferenceControlServiceTest': 18, 'InferenceControlConcurrencyTest': 14, 'InferenceControlHttpTest': 7, 'InferenceControlSqlTest': 18, 'InferenceControlMigrationTest': 4, 'InferenceControlFixtures': 0}
FEEDBACK_CLASS_COUNTS = {'StudyFeedbackCapacityTest': 14, 'StudyFeedbackConcurrencyTest': 3, 'StudyFeedbackDeletionTest': 14, 'StudyFeedbackFixtures': 0, 'StudyFeedbackHttpTest': 10, 'StudyFeedbackLifecycleTest': 13, 'StudyFeedbackMigrationTest': 2, 'StudyFeedbackResultTest': 8, 'StudyFeedbackServiceTest': 15, 'StudyFeedbackSourceTest': 15, 'StudyFeedbackSqlTest': 14, 'StudyFeedbackTransportTest': 15}
DIRECT_INPUT_METHOD = 'directInputRequiresIndependentlyObservedTargetAndClosedSourceKind'
STUDY_REVIEW_TASK_METHODS = (
    'independentChecklistTaskAndEncryptedCopiesRemainAfterQuiescentSourceDeletion',
    'independentIntentCanApplyAfterSourceDeletionWithoutLiveSourceAttestation',
    'independentTaskCreateAndExplicitCompletionNeverMutateStudyOrInferenceEvidence',
)

STUDY_LEARNING_CATALOG_METHODS = (
    'explicitLearningCatalogRecordsReachTypedLessonDispatch',
    'typedLessonLearningKeysHonorProjectOverrideAndTombstone',
    'typedLessonWithoutLearningRecordsAddsNoProfileDefaultsOrMemoryWrites',
)


def testcase(name=METHOD + "()", classname=CLASS, outcome="passed", extra=""):
    child = {"passed": "", "failed": '<failure message="' + MARKER + '" type="synthetic">' + MARKER + '</failure>',
             "errors": '<error message="synthetic">' + MARKER + '</error>',
             "skipped": '<skipped message="' + MARKER + '"/>'}[outcome]
    return '<testcase name=' + quoteattr(name) + ' classname=' + quoteattr(classname) + ' time="0.1">' + child + extra + '</testcase>'


def suite(cases, failures=0, errors=0, skipped=0, extra="", tests=None):
    return ('<testsuite name="synthetic" tests="' + str(len(cases) if tests is None else tests)
            + '" failures="' + str(failures) + '" errors="' + str(errors) + '" skipped="' + str(skipped)
            + '" timestamp="' + MARKER + '" hostname="' + MARKER + '">' + ''.join(cases) + extra + '</testsuite>').encode()


def complete_xml():
    return suite([testcase(name=method + "()", classname=report.PACKAGE + "." + cls)
                  for cls, names in report.ALLOWED_TESTS.items() for method in names])


class ParserTest(unittest.TestCase):
    def parse(self, data):
        return report.SuiteParser().parse(data)

    def rejected(self, data, code):
        with self.assertRaisesRegex(report.ReportError, '^' + code + '$'):
            self.parse(data)

    def test_actual_outcomes_and_ignored_payloads(self):
        cases = self.parse(suite([testcase(outcome=key) for key in report.OUTCOMES], failures=1, errors=1, skipped=1,
                                extra='<system-out>' + MARKER + '</system-out><system-err>' + MARKER + '</system-err>'))
        self.assertEqual([case['outcome'] for case in cases], list(report.OUTCOMES))
        self.assertNotIn(MARKER, json.dumps(cases))

    def test_dtd_internal_external_and_parameter_entities_rejected(self):
        for declaration in ('<!DOCTYPE testsuite [<!ENTITY p "SYNTHETIC">]>',
                            '<!DOCTYPE testsuite SYSTEM "file:///private/synthetic">',
                            '<!DOCTYPE testsuite [<!ENTITY % p SYSTEM "https://synthetic.invalid">%p;]>',
                            '<!DOCTYPE testsuite [<!ENTITY a "a"><!ENTITY b "&a;&a;">]>'):
            with self.subTest(declaration=declaration):
                self.rejected(declaration.encode() + suite([]), 'FORBIDDEN_XML_DECLARATION')

    def test_processing_instructions_and_bad_encoding_rejected(self):
        self.rejected(b'<?synthetic private="payload"?>' + suite([]), 'FORBIDDEN_XML_DECLARATION')
        self.rejected(b'\xff' + suite([]), 'MALFORMED_XML')
        self.rejected(suite([]).decode().encode('utf-16'), 'MALFORMED_XML')

    def test_malformed_inconsistent_negative_and_excessive_counts(self):
        self.rejected(b'<testsuite', 'MALFORMED_XML')
        self.rejected(suite([testcase()], tests=0), 'SUITE_COUNTS_MISMATCH')
        self.rejected(suite([], tests=-1), 'INVALID_SUITE_COUNTS')
        self.rejected(suite([], tests=10001), 'INVALID_SUITE_COUNTS')
        self.rejected(suite([], failures=1), 'SUITE_COUNTS_MISMATCH')

    def test_unrecognized_structure_attributes_namespaces_and_multi_outcomes(self):
        self.rejected(b'<testsuites/>', 'UNSUPPORTED_JUNIT_SCHEMA')
        self.rejected(suite([], extra='<unexpected>' + MARKER + '</unexpected>'), 'UNSUPPORTED_JUNIT_SCHEMA')
        self.rejected(suite([]).replace(b'<testsuite ', b'<testsuite private="secret" '), 'UNSUPPORTED_JUNIT_SCHEMA')
        self.rejected(suite([testcase(extra='<failure/><skipped/>')], failures=1), 'AMBIGUOUS_TESTCASE_OUTCOME')
        self.rejected(suite([testcase()]).replace(b'classname=', b'unknown='), 'UNSUPPORTED_JUNIT_SCHEMA')

    def test_retry_markers_are_not_silent_passes(self):
        for tag in report.SuiteParser.RETRIES:
            cases = self.parse(suite([testcase(extra='<' + tag + ' message="synthetic">' + MARKER + '</' + tag + '>')]))
            methods, counts = report.summarize(cases, {report.PACKAGE + '.' + name: 'c' * 64 for name in report.ALLOWED_TESTS})
            method = next(item for item in methods if item['class'] == CLASS and item['method'] == METHOD)
            self.assertEqual(method['outcome'], 'RETRY_UNRESOLVED')
            self.assertEqual(counts['retry_indicators'], 1)
            self.assertNotIn(MARKER, json.dumps(methods))

    def test_names_are_exactly_allowlisted_never_parameter_stripped(self):
        names = [METHOD, METHOD + '()', METHOD + '(synthetic-secret)', METHOD + '()[1]', MARKER, 'unknownMethod']
        cases = self.parse(suite([testcase(name=name) for name in names] + [testcase(classname=MARKER)]))
        methods, counts = report.summarize(cases, {report.PACKAGE + '.' + name: 'c' * 64 for name in report.ALLOWED_TESTS})
        self.assertEqual(counts['unmapped_testcases'], 5)
        self.assertEqual(counts['duplicate_observations'], 1)
        method = next(item for item in methods if item['class'] == CLASS and item['method'] == METHOD)
        self.assertEqual(method['outcome'], 'DUPLICATE_UNRESOLVED')
        self.assertNotIn(MARKER, json.dumps(methods))
        self.assertNotIn('synthetic-secret', json.dumps(methods))


class ReportFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'synthetic'
        self.root.mkdir()
        self.state = Path(self.temp.name) / 'state.json'
        # Generate minimal synthetic Java declarations, never copy real test data.
        for cls, names in report.ALLOWED_TESTS.items():
            path = self.root / report.TEST_ROOT / 'dev/myjarvis/core' / (cls + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package dev.myjarvis.core;\nclass ' + cls + ' {\n'
                            + ''.join('@Test void ' + method + '() {}\n' for method in names) + '}\n')
        for name in ('apps/core/src/main/java/dev/myjarvis/core/Synthetic.java',
                     'packages/contracts/fixtures/synthetic.json', 'build.gradle', 'settings.gradle',
                     'apps/core/build.gradle', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar',
                     'gradle/wrapper/gradle-wrapper.properties', 'scripts/check.sh',
                     'scripts/java_test_report.py', 'scripts/test_java_test_report.py', '.github/workflows/m0.yml'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('synthetic fixture\n')

    def prepare(self):
        report.prepare(self.root, self.state, CONTEXT)

    def xml(self, data=None, name='TEST-synthetic.xml'):
        path = self.root / report.REPORT_ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(complete_xml() if data is None else data)
        return path

    def emit(self, outcome='success'):
        return report.emit(self.root, self.state, CONTEXT, outcome)

    def test_complete_actual_observations_pass_and_bind_sources(self):
        self.prepare()
        self.xml()
        result = self.emit()
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['evidence'], 'PARSED_JUNIT_XML')
        expected = sum(map(len, report.ALLOWED_TESTS.values()))
        self.assertEqual(result['counts']['passed'], expected)
        self.assertEqual(result['counts']['observed_methods'], expected)
        self.assertEqual(result['counts']['unobserved_methods'], 0)
        self.assertEqual(result['context'], CONTEXT)
        self.assertEqual(len(result['source_hashes']), 5)
        self.assertTrue(all(item['outcome'] == 'PASS' for item in result['methods']))
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertNotIn(MARKER, json.dumps(result))
        self.assertEqual(stat_mode(self.state), 0o600)

    def test_one_report_file_per_reviewed_class_fits_finite_bound_and_preserves_all_methods(self):
        self.prepare()
        count=0
        for cls,names in report.ALLOWED_TESTS.items():
            if not names:continue
            self.xml(suite([testcase(name=name+'()',classname=report.PACKAGE+'.'+cls) for name in names]),'TEST-'+cls+'.xml')
            count+=1
        self.assertGreater(count,64)
        self.assertLessEqual(count,report.MAX_FILES)
        self.assertEqual(report.MAX_FILES,96)
        result=self.emit()
        self.assertEqual(result['status'],'PASS')
        self.assertEqual(result['counts']['observed_methods'],sum(map(len,report.ALLOWED_TESTS.values())))
        self.assertEqual(result['counts']['unobserved_methods'],0)
        self.assertEqual(result['counts']['unmapped_testcases'],0)
        self.assertNotIn(MARKER,json.dumps(result))

    def test_missing_preparation_and_never_run_java_are_unavailable(self):
        self.assertEqual(self.emit('skipped')['reason_codes'], ['PREPARATION_UNAVAILABLE'])
        self.prepare()
        for outcome in ('success', 'failure', 'skipped', 'cancelled'):
            result = self.emit(outcome)
            self.assertEqual(result['status'], 'UNAVAILABLE')
            self.assertEqual(result['reason_codes'], ['NO_JUNIT_XML_EXECUTION_UNESTABLISHED'])
            self.assertIsNone(result['counts'])
            self.assertEqual(result['methods'], [])

    def test_source_change_and_unknown_inventory_are_blocking(self):
        self.prepare()
        self.xml()
        source = self.root / 'apps/core/src/main/java/dev/myjarvis/core/Synthetic.java'
        source.write_text('changed synthetic source')
        self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])
        test = self.root / report.TEST_ROOT / 'dev/myjarvis/core/UnknownTest.java'
        test.write_text('package dev.myjarvis.core; class UnknownTest { @Test void unknown() {} }')
        self.assertEqual(self.emit()['reason_codes'], ['TEST_INVENTORY_REVIEW_REQUIRED'])

    def test_inventory_addition_rename_and_parameterized_tests_require_review(self):
        target = self.root / report.TEST_ROOT / 'dev/myjarvis/core/ConversationHistoryHttpTest.java'
        original = target.read_text()
        for source in (original.replace(METHOD, 'newMethod'),
                       original.replace('@Test void ' + METHOD + '()', '@Test void ' + METHOD + '(String secret)'),
                       original + '\n@Test void addedMethod() {}',
                       original.replace('@Test', '@ParameterizedTest', 1),
                       original.replace('@Test', '@DisplayName("synthetic") @Test', 1)):
            target.write_text(source)
            with self.assertRaisesRegex(report.ReportError, 'TEST_INVENTORY_REVIEW_REQUIRED'):
                report.source_snapshot(self.root)
        target.write_text(original + '\n// @Test void commentedOut() {}\nString s="@Test void literal() {}";')
        report.source_snapshot(self.root)

    def test_study_session_literal_mapping_and_source_hash_are_bound(self):
        self.prepare()
        self.xml()
        result = self.emit()
        methods = [item for item in result['methods']
                   if item['class'] == report.PACKAGE + '.StudySessionPolicyTest']
        target = self.root / report.TEST_ROOT / 'dev/myjarvis/core/StudySessionPolicyTest.java'
        self.assertEqual(len(methods), 12)
        self.assertEqual({item['method'] for item in methods}, set(report.ALLOWED_TESTS['StudySessionPolicyTest']))
        self.assertTrue(all(item['outcome'] == 'PASS' and item['observations'] == 1 for item in methods))
        self.assertTrue(all(item['source_sha256'] == hashlib.sha256(target.read_bytes()).hexdigest() for item in methods))
        target.write_text(target.read_text() + '\n// synthetic source binding change\n')
        self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])

    def test_study_session_extra_renamed_or_duplicate_method_is_rejected(self):
        target = self.root / report.TEST_ROOT / 'dev/myjarvis/core/StudySessionPolicyTest.java'
        original = target.read_text()
        first, second = report.ALLOWED_TESTS['StudySessionPolicyTest'][:2]
        for source in (original + '\n@Test void unreviewedThirteenthMethod() {}\n',
                       original.replace(first, 'unreviewedRenamedMethod'),
                       original.replace(second, first)):
            with self.subTest(source_kind=source != original):
                target.write_text(source)
                with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                    report.source_snapshot(self.root)
        target.write_text(original)
        report.source_snapshot(self.root)

    def test_study_answer_source_literal_mapping_binds_exact_source_bytes(self):
        self.prepare()
        self.xml()
        result = self.emit()
        methods = [item for item in result['methods']
                   if item['class'] == report.PACKAGE + '.StudyAnswerSourcePolicyTest']
        target = self.root / report.TEST_ROOT / 'dev/myjarvis/core/StudyAnswerSourcePolicyTest.java'
        self.assertEqual(len(methods), 15)
        self.assertEqual({item['method'] for item in methods}, set(report.ALLOWED_TESTS['StudyAnswerSourcePolicyTest']))
        self.assertTrue(all(item['outcome'] == 'PASS' and item['observations'] == 1 for item in methods))
        self.assertTrue(all(item['source_sha256'] == hashlib.sha256(target.read_bytes()).hexdigest() for item in methods))
        target.write_text(target.read_text() + '\n// synthetic source alteration\n')
        self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])

    def test_study_answer_source_missing_extra_renamed_duplicate_or_parameterized_is_rejected(self):
        target = self.root / report.TEST_ROOT / 'dev/myjarvis/core/StudyAnswerSourcePolicyTest.java'
        original = target.read_text()
        first, second = report.ALLOWED_TESTS['StudyAnswerSourcePolicyTest'][:2]
        for source in (original.replace('@Test void ' + first + '() {}\n', ''),
                       original + '\n@Test void unreviewedExtraMethod() {}\n',
                       original.replace(first, 'unreviewedRenamedMethod'), original.replace(second, first),
                       original.replace('@Test', '@ParameterizedTest', 1),
                       original.replace('@Test', '@DisplayName("synthetic") @Test', 1)):
            target.write_text(source)
            with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                report.source_snapshot(self.root)
        target.write_text(original)
        report.source_snapshot(self.root)

    def test_typed_lesson_exact_inventory_and_all_new_source_bindings(self):
        expected = {'StudyLessonContractTest': 8, 'StudyLessonHttpTest': 6, 'StudyLessonLifecycleTest': 21, 'StudyLessonMigrationTest': 1, 'StudyLessonSqlTest': 2}
        self.prepare(); self.xml(); result = self.emit()
        for name, count in expected.items():
            methods = [item for item in result['methods'] if item['class'] == report.PACKAGE + '.' + name]
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            self.assertEqual(len(methods), count)
            self.assertEqual({item['method'] for item in methods}, set(report.ALLOWED_TESTS[name]))
            self.assertTrue(all(item['outcome'] == 'PASS' and item['observations'] == 1 for item in methods))
            self.assertTrue(all(item['source_sha256'] == hashlib.sha256(target.read_bytes()).hexdigest() for item in methods))
        target.write_text(target.read_text() + '\n// synthetic changed lesson source\n')
        self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])

    def test_typed_lesson_inventory_rejects_missing_renamed_extra_and_parameterized(self):
        for name in ['StudyLessonContractTest', 'StudyLessonHttpTest', 'StudyLessonLifecycleTest', 'StudyLessonMigrationTest', 'StudyLessonSqlTest']:
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            original = target.read_text(); first = report.ALLOWED_TESTS[name][0]
            for source in (original.replace('@Test void ' + first + '() {}\n', ''),
                           original.replace(first, 'unreviewedLessonRename'),
                           original + '\n@Test void unreviewedLessonExtra() {}\n',
                           original.replace('@Test', '@ParameterizedTest', 1)):
                target.write_text(source)
                with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                    report.source_snapshot(self.root)
            target.write_text(original)
        report.source_snapshot(self.root)

    def test_inference_control_literal_inventory_and_every_source_hash_are_bound(self):
        self.prepare(); self.xml(); result = self.emit()
        self.assertEqual(result['status'], 'PASS')
        for name, count in CONTROL_CLASS_COUNTS.items():
            methods = [item for item in result['methods'] if item['class'] == report.PACKAGE + '.' + name]
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            self.assertEqual(len(methods), count)
            self.assertEqual({item['method'] for item in methods}, set(report.ALLOWED_TESTS[name]))
            self.assertTrue(all(item['outcome'] == 'PASS' and item['observations'] == 1 for item in methods))
            self.assertTrue(all(item['source_sha256'] == hashlib.sha256(target.read_bytes()).hexdigest() for item in methods))
            original = target.read_bytes()
            target.write_bytes(original + b'\n// synthetic source alteration\n')
            self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])
            target.write_bytes(original)
        self.assertEqual(self.emit()['status'], 'PASS')
        self.assertNotIn(MARKER, json.dumps(result))

    def test_inference_control_source_missing_renamed_extra_duplicate_parameterized_is_rejected(self):
        for name in CONTROL_CLASS_COUNTS:
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            original = target.read_text()
            mutations = [original + '\n@Test void unreviewedControlExtra() {}\n']
            if report.ALLOWED_TESTS[name]:
                first = report.ALLOWED_TESTS[name][0]
                mutations += [original.replace('@Test void ' + first + '() {}\n', ''),
                              original.replace(first, 'unreviewedControlRename'),
                              original.replace('@Test void ' + first + '() {}', '@Test void ' + first + '() {} @Test void ' + first + '() {}'),
                              original.replace('@Test', '@ParameterizedTest', 1),
                              original.replace('@Test', '@RepeatedTest(2)', 1),
                              original.replace('@Test', '@DisplayName("synthetic") @Test', 1),
                              original.replace('@Test void ' + first + '()', '@Test void ' + first + '(String synthetic)')]
            for source in mutations:
                target.write_text(source)
                with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                    report.source_snapshot(self.root)
            target.write_text(original)
        report.source_snapshot(self.root)

    def test_inference_control_class_package_and_nested_identity_substitutions_are_rejected(self):
        self.prepare(); self.xml()
        for name in CONTROL_CLASS_COUNTS:
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            original = target.read_text()
            for source in (original.replace('class ' + name, 'class UnreviewedIdentity'),
                           original.replace('package dev.myjarvis.core;', 'package synthetic.privatevalue;'),
                           original + '\nclass UnreviewedSibling {}\n',
                           original.replace('class ' + name + ' {', 'class UnreviewedOuter { class ' + name + ' {') + '}\n',
                           original.replace('class ' + name + ' {', 'class ' + name + ' { class UnreviewedNested {') + '}\n'):
                # Empty fixtures can contain nested helpers; test-bearing classes
                # must never relocate reviewed methods into a nested identity.
                if 'UnreviewedNested' in source and not report.ALLOWED_TESTS[name]:
                    continue
                target.write_text(source)
                result = self.emit()
                self.assertEqual(result['reason_codes'], ['TEST_INVENTORY_REVIEW_REQUIRED'])
                self.assertNotIn(MARKER, json.dumps(result))
                self.assertNotIn('synthetic.privatevalue', json.dumps(result))
            target.write_text(original)
        self.assertEqual(self.emit()['status'], 'PASS')

    def test_inference_control_xml_missing_renamed_extra_duplicate_parameterized_and_wrong_class_fail_closed(self):
        self.prepare()
        for name in CONTROL_CLASS_COUNTS:
            if not report.ALLOWED_TESTS[name]:
                continue
            cls = report.PACKAGE + '.' + name
            method = report.ALLOWED_TESTS[name][0]
            matched = testcase(name=method + '()', classname=cls)
            rest = [testcase(name=m + '()', classname=report.PACKAGE + '.' + c)
                    for c, names in report.ALLOWED_TESTS.items() for m in names
                    if (c, m) != (name, method)]
            for cases, unobserved, unmapped, duplicate in (
                    (rest, 1, 0, 0),
                    (rest + [testcase(name=MARKER, classname=cls)], 1, 1, 0),
                    (rest + [matched, testcase(name=MARKER, classname=cls)], 0, 1, 0),
                    (rest + [matched, matched], 0, 0, 1),
                    (rest + [testcase(name=method + '(' + MARKER + ')', classname=cls)], 1, 1, 0),
                    (rest + [testcase(name=method + '()', classname=MARKER)], 1, 1, 0)):
                self.xml(suite(cases))
                result = self.emit()
                self.assertEqual(result['status'], 'INCOMPLETE')
                self.assertEqual(result['counts']['unobserved_methods'], unobserved)
                self.assertEqual(result['counts']['unmapped_testcases'], unmapped)
                self.assertEqual(result['counts']['duplicate_observations'], duplicate)
                self.assertNotIn(MARKER, json.dumps(result))
                self.assertNotIn(str(self.root), json.dumps(result))

    def test_durable_study_literal_inventory_and_every_source_hash_are_bound(self):
        self.prepare(); self.xml(); result = self.emit()
        self.assertEqual(result['status'], 'PASS')
        for name, count in DURABLE_CLASS_COUNTS.items():
            methods = [item for item in result['methods'] if item['class'] == report.PACKAGE + '.' + name]
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            self.assertEqual(len(methods), count)
            self.assertEqual({item['method'] for item in methods}, set(report.ALLOWED_TESTS[name]))
            self.assertTrue(all(item['outcome'] == 'PASS' and item['observations'] == 1 for item in methods))
            self.assertTrue(all(item['source_sha256'] == hashlib.sha256(target.read_bytes()).hexdigest() for item in methods))
            original = target.read_bytes()
            target.write_bytes(original + b'\n// synthetic source alteration\n')
            self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])
            target.write_bytes(original)
        self.assertEqual(self.emit()['status'], 'PASS')
        self.assertNotIn(MARKER, json.dumps(result))

    def test_durable_study_source_missing_renamed_extra_duplicate_parameterized_is_rejected(self):
        for name in DURABLE_CLASS_COUNTS:
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            original = target.read_text()
            mutations = [original + '\n@Test void unreviewedDurableExtra() {}\n']
            if report.ALLOWED_TESTS[name]:
                first = report.ALLOWED_TESTS[name][0]
                mutations += [original.replace('@Test void ' + first + '() {}\n', ''),
                              original.replace(first, 'unreviewedDurableRename'),
                              original.replace('@Test void ' + first + '() {}', '@Test void ' + first + '() {} @Test void ' + first + '() {}'),
                              original.replace('@Test', '@ParameterizedTest', 1),
                              original.replace('@Test', '@RepeatedTest(2)', 1),
                              original.replace('@Test', '@DisplayName("synthetic") @Test', 1),
                              original.replace('@Test void ' + first + '()', '@Test void ' + first + '(String synthetic)')]
            for source in mutations:
                target.write_text(source)
                with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                    report.source_snapshot(self.root)
            target.write_text(original)
        report.source_snapshot(self.root)

    def test_durable_study_class_package_and_nested_identity_substitutions_are_rejected(self):
        self.prepare(); self.xml()
        for name in DURABLE_CLASS_COUNTS:
            target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
            original = target.read_text()
            for source in (original.replace('class ' + name, 'class UnreviewedIdentity'),
                           original.replace('package dev.myjarvis.core;', 'package synthetic.privatevalue;'),
                           original + '\nclass UnreviewedSibling {}\n',
                           original.replace('class ' + name + ' {', 'class UnreviewedOuter { class ' + name + ' {') + '}\n',
                           original.replace('class ' + name + ' {', 'class ' + name + ' { class UnreviewedNested {') + '}\n'):
                # Empty fixtures can contain nested helpers; test-bearing classes
                # must never relocate reviewed methods into a nested identity.
                if 'UnreviewedNested' in source and not report.ALLOWED_TESTS[name]:
                    continue
                target.write_text(source)
                result = self.emit()
                self.assertEqual(result['reason_codes'], ['TEST_INVENTORY_REVIEW_REQUIRED'])
                self.assertNotIn(MARKER, json.dumps(result))
                self.assertNotIn('synthetic.privatevalue', json.dumps(result))
            target.write_text(original)
        self.assertEqual(self.emit()['status'], 'PASS')

    def test_durable_study_xml_missing_renamed_extra_duplicate_parameterized_and_wrong_class_fail_closed(self):
        self.prepare()
        for name in DURABLE_CLASS_COUNTS:
            if not report.ALLOWED_TESTS[name]:
                continue
            cls = report.PACKAGE + '.' + name
            method = report.ALLOWED_TESTS[name][0]
            matched = testcase(name=method + '()', classname=cls)
            rest = [testcase(name=m + '()', classname=report.PACKAGE + '.' + c)
                    for c, names in report.ALLOWED_TESTS.items() for m in names
                    if (c, m) != (name, method)]
            for cases, unobserved, unmapped, duplicate in (
                    (rest, 1, 0, 0),
                    (rest + [testcase(name=MARKER, classname=cls)], 1, 1, 0),
                    (rest + [matched, testcase(name=MARKER, classname=cls)], 0, 1, 0),
                    (rest + [matched, matched], 0, 0, 1),
                    (rest + [testcase(name=method + '(' + MARKER + ')', classname=cls)], 1, 1, 0),
                    (rest + [testcase(name=method + '()', classname=MARKER)], 1, 1, 0)):
                self.xml(suite(cases))
                result = self.emit()
                self.assertEqual(result['status'], 'INCOMPLETE')
                self.assertEqual(result['counts']['unobserved_methods'], unobserved)
                self.assertEqual(result['counts']['unmapped_testcases'], unmapped)
                self.assertEqual(result['counts']['duplicate_observations'], duplicate)
                self.assertNotIn(MARKER, json.dumps(result))
                self.assertNotIn(str(self.root), json.dumps(result))

    def test_direct_owner_input_added_identity_cannot_be_omitted_or_parameterized(self):
        self.assertIn(DIRECT_INPUT_METHOD, report.ALLOWED_TESTS['StudyAnswerSourcePolicyTest'])
        target = self.root / report.TEST_ROOT / 'dev/myjarvis/core/StudyAnswerSourcePolicyTest.java'
        original = target.read_text()
        declaration = '@Test void ' + DIRECT_INPUT_METHOD + '() {}'
        for source in (original.replace(declaration, ''),
                       original.replace(declaration, '@ParameterizedTest void ' + DIRECT_INPUT_METHOD + '() {}'),
                       original.replace(DIRECT_INPUT_METHOD, 'unreviewedDirectInputRename')):
            target.write_text(source)
            with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                report.source_snapshot(self.root)
        target.write_text(original)
        self.prepare(); self.xml(); result = self.emit()
        methods = [item for item in result['methods'] if item['class'] == report.PACKAGE + '.StudyAnswerSourcePolicyTest'
                   and item['method'] == DIRECT_INPUT_METHOD]
        self.assertEqual(len(methods), 1)
        self.assertEqual(methods[0]['source_sha256'], hashlib.sha256(target.read_bytes()).hexdigest())

    def test_missing_method_is_not_observed_not_a_pass(self):
        self.prepare()
        self.xml(suite([testcase()]))
        result = self.emit()
        self.assertEqual(result['status'], 'INCOMPLETE')
        self.assertEqual(result['counts']['passed'], 1)
        self.assertEqual(result['counts']['observed_methods'], 1)
        self.assertGreater(result['counts']['unobserved_methods'], 0)
        self.assertIn('NOT_OBSERVED', {item['outcome'] for item in result['methods']})

    def test_study_review_task_exact_names_and_source_bytes_bind_each_synthetic_observation(self):
        name = 'StudyFeedbackDeletionTest'
        cls = report.PACKAGE + '.' + name
        target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
        self.assertEqual(report.ALLOWED_TESTS[name][-3:], STUDY_REVIEW_TASK_METHODS)
        self.assertEqual(len(report.ALLOWED_TESTS[name]), 14)
        self.prepare()
        for suffix in ('', '()'):
            with self.subTest(default_name_suffix=suffix):
                self.xml(suite([testcase(name=method + (suffix if (c == name and method in STUDY_REVIEW_TASK_METHODS)
                                                          else '()'), classname=report.PACKAGE + '.' + c)
                                for c, names in report.ALLOWED_TESTS.items() for method in names]))
                result = self.emit()
                self.assertEqual(result['status'], 'PASS')
                self.assertEqual(result['evidence'], 'PARSED_JUNIT_XML')
                self.assertEqual(result['counts']['observed_methods'], 812)
                self.assertEqual(result['counts']['unobserved_methods'], 0)
                self.assertEqual(result['counts']['unmapped_testcases'], 0)
                for method in STUDY_REVIEW_TASK_METHODS:
                    observed = [item for item in result['methods'] if (item['class'], item['method']) == (cls, method)]
                    self.assertEqual(len(observed), 1)
                    self.assertEqual(observed[0]['outcome'], 'PASS')
                    self.assertEqual(observed[0]['observations'], 1)
                    self.assertEqual(observed[0]['counts'], {'passed': 1, 'failed': 0, 'errors': 0, 'skipped': 0})
                    self.assertEqual(observed[0]['source_sha256'], hashlib.sha256(target.read_bytes()).hexdigest())
                self.assertNotIn(MARKER, json.dumps(result))
                self.assertNotIn(str(self.root), json.dumps(result))
        original = target.read_bytes()
        target.write_bytes(original + b'\n// synthetic study review source alteration\n')
        self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])
        target.write_bytes(original)
        self.assertEqual(self.emit()['status'], 'PASS')

    def test_each_study_review_task_name_rejects_source_and_xml_substitutions_or_omissions(self):
        name = 'StudyFeedbackDeletionTest'
        cls = report.PACKAGE + '.' + name
        target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
        original = target.read_text()
        for method in STUDY_REVIEW_TASK_METHODS:
            declaration = '@Test void ' + method + '() {}'
            for kind, replacement in (
                    ('missing', ''),
                    ('renamed', '@Test void unreviewedStudyReviewRename() {}'),
                    ('extra', declaration + '\n@Test void unreviewedStudyReviewExtra() {}'),
                    ('duplicate', declaration + '\n' + declaration),
                    ('parameterized', declaration.replace('@Test', '@ParameterizedTest')),
                    ('repeated', declaration.replace('@Test', '@RepeatedTest(2)')),
                    ('display_name', declaration.replace('@Test', '@DisplayName("synthetic") @Test')),
                    ('argument', declaration.replace('()', '(String synthetic)')),
                    ('nested', 'class UnreviewedNested { ' + declaration + ' }')):
                with self.subTest(method=method, source_mutation=kind):
                    target.write_text(original.replace(declaration, replacement))
                    with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                        report.source_snapshot(self.root)
            target.write_text(original)
        report.source_snapshot(self.root)
        self.prepare()
        for method in STUDY_REVIEW_TASK_METHODS:
            matched = testcase(name=method + '()', classname=cls)
            rest = [testcase(name=m + '()', classname=report.PACKAGE + '.' + c)
                    for c, names in report.ALLOWED_TESTS.items() for m in names if (c, m) != (name, method)]
            for kind, additions, unobserved, unmapped, duplicate, outcome in (
                    ('missing', [], 1, 0, 0, 'NOT_OBSERVED'),
                    ('renamed', [testcase(name=MARKER, classname=cls)], 1, 1, 0, 'NOT_OBSERVED'),
                    ('extra', [matched, testcase(name=MARKER, classname=cls)], 0, 1, 0, 'PASS'),
                    ('duplicate', [matched, matched], 0, 0, 1, 'DUPLICATE_UNRESOLVED'),
                    ('parameterized', [testcase(name=method + '(' + MARKER + ')', classname=cls)], 1, 1, 0, 'NOT_OBSERVED'),
                    ('display_suffix', [testcase(name=method + '()[1]', classname=cls)], 1, 1, 0, 'NOT_OBSERVED'),
                    ('wrong_class', [testcase(name=method + '()', classname=CLASS)], 1, 1, 0, 'NOT_OBSERVED')):
                with self.subTest(method=method, xml_mutation=kind):
                    self.xml(suite(rest + additions))
                    result = self.emit()
                    self.assertEqual(result['status'], 'INCOMPLETE')
                    self.assertEqual(result['counts']['observed_methods'], 812 - unobserved)
                    self.assertEqual(result['counts']['unobserved_methods'], unobserved)
                    self.assertEqual(result['counts']['unmapped_testcases'], unmapped)
                    self.assertEqual(result['counts']['duplicate_observations'], duplicate)
                    observed = [item for item in result['methods'] if (item['class'], item['method']) == (cls, method)]
                    self.assertEqual(len(observed), 1)
                    self.assertEqual(observed[0]['outcome'], outcome)
                    self.assertEqual(observed[0]['observations'], 0 if unobserved else 1 + duplicate)
                    self.assertEqual(observed[0]['source_sha256'], hashlib.sha256(target.read_bytes()).hexdigest())
                    if unobserved:
                        self.assertIn('UNOBSERVED_METHODS', result['reason_codes'])
                        self.assertEqual(observed[0]['counts'], {key: 0 for key in report.OUTCOMES})
                    self.assertNotIn(MARKER, json.dumps(result))
                    self.assertNotIn(str(self.root), json.dumps(result))

    def test_study_learning_catalog_exact_names_and_source_bytes_bind_each_synthetic_observation(self):
        name = 'StudyLessonLifecycleTest'
        cls = report.PACKAGE + '.' + name
        target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
        self.assertEqual(report.ALLOWED_TESTS[name][-3:], STUDY_LEARNING_CATALOG_METHODS)
        self.assertEqual(len(report.ALLOWED_TESTS[name]), 21)
        self.prepare()
        for suffix in ('', '()'):
            with self.subTest(default_name_suffix=suffix):
                self.xml(suite([testcase(name=method + (suffix if (c == name and method in STUDY_LEARNING_CATALOG_METHODS)
                                                          else '()'), classname=report.PACKAGE + '.' + c)
                                for c, names in report.ALLOWED_TESTS.items() for method in names]))
                result = self.emit()
                self.assertEqual(result['status'], 'PASS')
                self.assertEqual(result['evidence'], 'PARSED_JUNIT_XML')
                self.assertEqual(result['counts']['observed_methods'], 812)
                self.assertEqual(result['counts']['unobserved_methods'], 0)
                self.assertEqual(result['counts']['unmapped_testcases'], 0)
                for method in STUDY_LEARNING_CATALOG_METHODS:
                    observed = [item for item in result['methods'] if (item['class'], item['method']) == (cls, method)]
                    self.assertEqual(len(observed), 1)
                    self.assertEqual(observed[0]['outcome'], 'PASS')
                    self.assertEqual(observed[0]['observations'], 1)
                    self.assertEqual(observed[0]['counts'], {'passed': 1, 'failed': 0, 'errors': 0, 'skipped': 0})
                    self.assertEqual(observed[0]['source_sha256'], hashlib.sha256(target.read_bytes()).hexdigest())
                self.assertNotIn(MARKER, json.dumps(result))
                self.assertNotIn(str(self.root), json.dumps(result))
        original = target.read_bytes()
        target.write_bytes(original + b'\n// synthetic learning catalog source alteration\n')
        self.assertEqual(self.emit()['reason_codes'], ['SOURCE_CHANGED_DURING_VERIFICATION'])
        target.write_bytes(original)
        self.assertEqual(self.emit()['status'], 'PASS')

    def test_each_study_learning_catalog_name_rejects_source_and_xml_substitutions_or_omissions(self):
        name = 'StudyLessonLifecycleTest'
        cls = report.PACKAGE + '.' + name
        target = self.root / report.TEST_ROOT / ('dev/myjarvis/core/' + name + '.java')
        original = target.read_text()
        for method in STUDY_LEARNING_CATALOG_METHODS:
            declaration = '@Test void ' + method + '() {}'
            for kind, replacement in (
                    ('missing', ''),
                    ('renamed', '@Test void unreviewedLearningCatalogRename() {}'),
                    ('extra', declaration + '\n@Test void unreviewedLearningCatalogExtra() {}'),
                    ('duplicate', declaration + '\n' + declaration),
                    ('parameterized', declaration.replace('@Test', '@ParameterizedTest')),
                    ('repeated', declaration.replace('@Test', '@RepeatedTest(2)')),
                    ('display_name', declaration.replace('@Test', '@DisplayName("synthetic") @Test')),
                    ('argument', declaration.replace('()', '(String synthetic)')),
                    ('nested', 'class UnreviewedNested { ' + declaration + ' }')):
                with self.subTest(method=method, source_mutation=kind):
                    target.write_text(original.replace(declaration, replacement))
                    with self.assertRaisesRegex(report.ReportError, '^TEST_INVENTORY_REVIEW_REQUIRED$'):
                        report.source_snapshot(self.root)
            target.write_text(original)
        report.source_snapshot(self.root)
        self.prepare()
        for method in STUDY_LEARNING_CATALOG_METHODS:
            matched = testcase(name=method + '()', classname=cls)
            rest = [testcase(name=m + '()', classname=report.PACKAGE + '.' + c)
                    for c, names in report.ALLOWED_TESTS.items() for m in names if (c, m) != (name, method)]
            for kind, additions, unobserved, unmapped, duplicate, outcome in (
                    ('missing', [], 1, 0, 0, 'NOT_OBSERVED'),
                    ('renamed', [testcase(name=MARKER, classname=cls)], 1, 1, 0, 'NOT_OBSERVED'),
                    ('extra', [matched, testcase(name=MARKER, classname=cls)], 0, 1, 0, 'PASS'),
                    ('duplicate', [matched, matched], 0, 0, 1, 'DUPLICATE_UNRESOLVED'),
                    ('parameterized', [testcase(name=method + '(' + MARKER + ')', classname=cls)], 1, 1, 0, 'NOT_OBSERVED'),
                    ('display_suffix', [testcase(name=method + '()[1]', classname=cls)], 1, 1, 0, 'NOT_OBSERVED'),
                    ('wrong_class', [testcase(name=method + '()', classname=CLASS)], 1, 1, 0, 'NOT_OBSERVED')):
                with self.subTest(method=method, xml_mutation=kind):
                    self.xml(suite(rest + additions))
                    result = self.emit()
                    self.assertEqual(result['status'], 'INCOMPLETE')
                    self.assertEqual(result['counts']['observed_methods'], 812 - unobserved)
                    self.assertEqual(result['counts']['unobserved_methods'], unobserved)
                    self.assertEqual(result['counts']['unmapped_testcases'], unmapped)
                    self.assertEqual(result['counts']['duplicate_observations'], duplicate)
                    observed = [item for item in result['methods'] if (item['class'], item['method']) == (cls, method)]
                    self.assertEqual(len(observed), 1)
                    self.assertEqual(observed[0]['outcome'], outcome)
                    self.assertEqual(observed[0]['observations'], 0 if unobserved else 1 + duplicate)
                    self.assertEqual(observed[0]['source_sha256'], hashlib.sha256(target.read_bytes()).hexdigest())
                    if unobserved:
                        self.assertIn('UNOBSERVED_METHODS', result['reason_codes'])
                        self.assertEqual(observed[0]['counts'], {key: 0 for key in report.OUTCOMES})
                    self.assertNotIn(MARKER, json.dumps(result))
                    self.assertNotIn(str(self.root), json.dumps(result))

    def test_duplicates_across_files_and_unmapped_names_fail_closed(self):
        self.prepare()
        self.xml()
        self.xml(suite([testcase(), testcase(name=MARKER)]), 'TEST-extra.xml')
        result = self.emit()
        self.assertEqual(result['status'], 'INCOMPLETE')
        self.assertEqual(result['counts']['duplicate_observations'], 1)
        self.assertEqual(result['counts']['unmapped_testcases'], 1)
        self.assertNotIn(MARKER, json.dumps(result))

    def test_skips_errors_failures_and_other_verification_failure_are_not_green(self):
        self.prepare()
        for outcome, counters, expected in (('skipped', {'skipped': 1}, 'INCOMPLETE'),
                                            ('failed', {'failures': 1}, 'FAIL'),
                                            ('errors', {'errors': 1}, 'FAIL')):
            self.xml(suite([testcase(outcome=outcome)], **counters))
            self.assertEqual(self.emit()['status'], expected)
        self.xml()
        self.assertEqual(self.emit('failure')['status'], 'INCOMPLETE')
        self.assertIn('VERIFICATION_NOT_SUCCESS', self.emit('failure')['reason_codes'])

    def test_zero_testcases_and_malformed_xml_cannot_produce_zero_pass(self):
        self.prepare()
        self.xml(suite([]))
        result = self.emit()
        self.assertEqual(result['status'], 'INCOMPLETE')
        self.assertIn('NO_TESTCASES', result['reason_codes'])
        self.xml(b'<private-' + MARKER.encode())
        result = self.emit()
        self.assertEqual(result['status'], 'INVALID')
        self.assertEqual(result['evidence'], 'NONE')
        self.assertIsNone(result['counts'])
        self.assertNotIn(MARKER, json.dumps(result))

    def test_state_reuse_wrong_run_and_stale_or_preexisting_xml_are_rejected(self):
        self.prepare()
        with self.assertRaisesRegex(report.ReportError, 'STATE_WRITE_UNAVAILABLE'):
            self.prepare()
        other = dict(CONTEXT, run_attempt='3')
        self.assertEqual(report.emit(self.root, self.state, other, 'success')['reason_codes'], ['PREPARATION_CONTEXT_MISMATCH'])
        xml = self.xml()
        os.utime(xml, ns=(1, 1))
        self.assertEqual(self.emit()['reason_codes'], ['STALE_JUNIT_REPORT'])
        with self.assertRaisesRegex(report.ReportError, 'PREEXISTING_JUNIT_REPORTS'):
            report.prepare(self.root, self.state.with_suffix('.other'), CONTEXT)

    def test_symlinks_size_limits_and_file_limits_are_rejected(self):
        self.prepare()
        xml = self.xml()
        xml.unlink()
        xml.symlink_to(self.root / 'build.gradle')
        self.assertEqual(self.emit()['reason_codes'], ['UNSAFE_INPUT'])
        xml.unlink()
        self.xml(b'x' * 100)
        with patch.object(report, 'MAX_FILE_BYTES', 20):
            # regular_bytes also receives a bounded explicit limit in direct use.
            with self.assertRaisesRegex(report.ReportError, 'INPUT_SIZE_OR_TYPE'):
                report.regular_bytes(xml, self.root, 20)
        with patch.object(report, 'MAX_TOTAL_BYTES', 20):
            self.assertEqual(self.emit()['reason_codes'], ['REPORT_SIZE_LIMIT'])
        with patch.object(report, 'MAX_FILES', 0):
            self.assertEqual(self.emit()['reason_codes'], ['REPORT_SIZE_LIMIT'])

    def test_unexpected_errors_never_disclose_exception_text(self):
        with patch.object(report, 'emit', side_effect=RuntimeError(MARKER)), patch.dict(os.environ, ENVIRONMENT, clear=True):
            output = io.StringIO()
            errors = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                self.assertEqual(report.main(['emit', '--state', str(self.state)]), 1)
            self.assertNotIn(MARKER, output.getvalue())
            self.assertEqual(errors.getvalue(), '')
            self.assertEqual(json.loads(output.getvalue().splitlines()[0])['reason_codes'], ['INTERNAL_REPORT_ERROR'])

    def test_cli_uses_only_validated_context_and_sanitized_fixed_error_codes(self):
        with patch.object(report, 'ROOT', self.root), patch.dict(os.environ, ENVIRONMENT, clear=True):
            self.assertEqual(report.main(['prepare', '--state', str(self.state)]), 0)
            self.xml()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(report.main(['emit', '--state', str(self.state), '--verification-outcome', 'success']), 0)
            lines = output.getvalue().splitlines()
            records = [json.loads(line) for line in lines]
            self.assertEqual(records[0]['status'], 'PASS')
            self.assertTrue(records[0]['complete_requires_end_record'])
            self.assertEqual(records[-1]['record'], 'java_test_report_end')
            self.assertEqual(records[-1]['content_sha256'], hashlib.sha256(('\n'.join(lines[:-1]) + '\n').encode()).hexdigest())
            self.assertEqual(records[0]['method_records_expected'], len(records) - 2)
            self.assertEqual(records[-1]['method_records_emitted'], len(records) - 2)
            self.assertEqual([item['sequence'] for item in records[1:-1]], list(range(1, len(records) - 1)))
            self.assertEqual(len({item['report_id'] for item in records}), 1)
            self.assertTrue(all(len(line.encode()) <= 8192 for line in lines))
            self.assertNotIn(str(self.root), output.getvalue())
            self.assertNotIn(MARKER, output.getvalue())
        with patch.dict(os.environ, dict(ENVIRONMENT, REPORT_CHECKOUT_SHA=MARKER), clear=True):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(report.main(['emit', '--state', str(self.state)]), 1)
            self.assertNotIn(MARKER, output.getvalue())
            self.assertEqual(json.loads(output.getvalue().splitlines()[0])['reason_codes'], ['INVALID_CONTEXT'])


def stat_mode(path):
    return path.stat().st_mode & 0o777


class SourceAndWorkflowGuardTest(unittest.TestCase):
    def test_current_source_inventory_matches_reviewed_names_not_execution(self):
        snapshot = report.source_snapshot(report.ROOT)
        self.assertEqual(len(snapshot['class_source_sha256']), len(report.ALLOWED_TESTS))
        # This assertion is inventory evidence only; no Java execution is claimed.
        self.assertEqual(sum(map(len, report.ALLOWED_TESTS.values())), 812)

    def test_all_569_historical_identities_remain_exactly_preserved(self):
        historical = sorted(cls + '.' + method for cls, names in report.ALLOWED_TESTS.items() for method in names
                            if cls not in DURABLE_CLASS_COUNTS and cls not in CONTROL_CLASS_COUNTS and cls not in FEEDBACK_CLASS_COUNTS
                            and (cls, method) != ('StudyAnswerSourcePolicyTest', DIRECT_INPUT_METHOD)
                            and (cls, method) not in {('StudyLessonLifecycleTest', name) for name in STUDY_LEARNING_CATALOG_METHODS})
        self.assertEqual(len(historical), 569)
        self.assertEqual(hashlib.sha256(('\n'.join(historical) + '\n').encode()).hexdigest(),
                         'eaf1fb4616a8dbcd87fc28744000afa9778575c98003332e734c11fbe43b25fa')
        self.assertEqual({name: len(report.ALLOWED_TESTS[name]) for name in DURABLE_CLASS_COUNTS}, DURABLE_CLASS_COUNTS)
        self.assertEqual(len(set(report.ALLOWED_TESTS) - set(CONTROL_CLASS_COUNTS) - set(FEEDBACK_CLASS_COUNTS)), 56)

    def test_all_618_previous_identities_remain_exactly_preserved(self):
        historical = sorted(cls + '.' + method for cls, names in report.ALLOWED_TESTS.items()
                            if cls not in CONTROL_CLASS_COUNTS and cls not in FEEDBACK_CLASS_COUNTS for method in names
                            if (cls, method) not in {('StudyLessonLifecycleTest', name) for name in STUDY_LEARNING_CATALOG_METHODS})
        self.assertEqual(len(historical), 618)
        self.assertEqual(hashlib.sha256(('\n'.join(historical) + '\n').encode()).hexdigest(),
                         'bf3c9ac564e491e5425a92ad0260232a6adaecb7fec1edac94dbd89d51ef3c73')
        self.assertEqual({name: len(report.ALLOWED_TESTS[name]) for name in CONTROL_CLASS_COUNTS}, CONTROL_CLASS_COUNTS)
        self.assertEqual(len(set(report.ALLOWED_TESTS) - set(FEEDBACK_CLASS_COUNTS)), 63)

    def test_all_686_stage_a_identities_remain_exactly_preserved(self):
        historical=sorted(cls+'.'+method for cls,names in report.ALLOWED_TESTS.items()
                          if cls not in FEEDBACK_CLASS_COUNTS for method in names
                          if (cls, method) not in {('StudyLessonLifecycleTest', name) for name in STUDY_LEARNING_CATALOG_METHODS})
        self.assertEqual(len(historical),686)
        self.assertEqual(hashlib.sha256(('\n'.join(historical)+'\n').encode()).hexdigest(),
                         '2c3ddbe76e953f3499193316def7d130dccd9196f60f157ca4f3bb1ce3d70ff3')
        self.assertEqual({name:len(report.ALLOWED_TESTS[name]) for name in FEEDBACK_CLASS_COUNTS},FEEDBACK_CLASS_COUNTS)
        self.assertEqual(len(report.ALLOWED_TESTS),75)
        self.assertLessEqual(sum(bool(names) for names in report.ALLOWED_TESTS.values()),report.MAX_FILES)

    def test_all_806_pre_review_task_identities_remain_exactly_preserved(self):
        additions = {('StudyFeedbackDeletionTest', method) for method in STUDY_REVIEW_TASK_METHODS}
        historical = sorted(cls + '.' + method for cls, names in report.ALLOWED_TESTS.items()
                            for method in names if (cls, method) not in additions
                            and (cls, method) not in {('StudyLessonLifecycleTest', name) for name in STUDY_LEARNING_CATALOG_METHODS})
        self.assertEqual(len(additions), 3)
        self.assertEqual(report.ALLOWED_TESTS['StudyFeedbackDeletionTest'][-3:], STUDY_REVIEW_TASK_METHODS)
        self.assertEqual(len(report.ALLOWED_TESTS['StudyFeedbackDeletionTest'][:-3]), 11)
        self.assertEqual(len(historical), 806)
        # Frozen parent inventory: sorted unqualified class.method lines and final newline.
        self.assertEqual(hashlib.sha256(('\n'.join(historical) + '\n').encode()).hexdigest(),
                         '02c61eb5bb7d73e003165163726bcfebdc85480f503c306424531a721e666840')

    def test_all_809_parent_identities_remain_exactly_preserved(self):
        additions = {('StudyLessonLifecycleTest', method) for method in STUDY_LEARNING_CATALOG_METHODS}
        self.assertEqual(len(additions), 3)
        self.assertEqual(report.ALLOWED_TESTS['StudyLessonLifecycleTest'][-3:], STUDY_LEARNING_CATALOG_METHODS)
        self.assertEqual(len(report.ALLOWED_TESTS['StudyLessonLifecycleTest'][:-3]), 18)
        snapshot = report.source_snapshot(report.ROOT)
        target = report.ROOT / report.TEST_ROOT / 'dev/myjarvis/core/StudyLessonLifecycleTest.java'
        self.assertEqual(snapshot['class_source_sha256'][report.PACKAGE + '.StudyLessonLifecycleTest'],
                         hashlib.sha256(target.read_bytes()).hexdigest())
        for _, method in additions:
            self.assertEqual(report.ALLOWED_TESTS['StudyLessonLifecycleTest'].count(method), 1)
            self.assertEqual(target.read_text().count('@Test void ' + method + '()'), 1)
        historical = sorted(cls + '.' + method for cls, names in report.ALLOWED_TESTS.items()
                            for method in names if (cls, method) not in additions)
        self.assertEqual(len(historical), 809)
        self.assertEqual(len(set(historical)), 809)
        self.assertEqual(hashlib.sha256(('\n'.join(historical) + '\n').encode()).hexdigest(),
                         '403eb5d44b4fdcbddc518707fb77d031e5ebe668e56e3995514675e5aff3024b')

    def test_control_migration_adds_v17_without_changing_any_previous_migration(self):
        path = report.ROOT / report.TEST_ROOT / 'dev/myjarvis/core/InferenceControlMigrationTest.java'
        source = path.read_text()
        self.assertIn('migration(ds,"16").migrate()', source)
        self.assertIn('migration(ds,"17").migrate()', source)
        self.assertIn('assertEquals(16,expected.size())', source)
        self.assertIn('/migrations/pre-v17-sha256.properties', source)
        fixture = report.ROOT / 'apps/core/src/test/resources/migrations/pre-v17-sha256.properties'
        expected = dict(line.split('=', 1) for line in fixture.read_text().splitlines() if line and not line.startswith('#'))
        self.assertEqual(len(expected), 16)
        for name, digest in expected.items():
            migration = report.ROOT / 'apps/core/src/main/resources/db/migration' / name
            self.assertEqual(hashlib.sha256(migration.read_bytes()).hexdigest(), digest)
        self.assertEqual(len(list((report.ROOT / 'apps/core/src/main/resources/db/migration').glob('V*.sql'))), 18)

    def test_historical_and_durable_migration_tests_keep_distinct_version_boundaries(self):
        self.assertEqual(report.ALLOWED_TESTS['StudyLessonMigrationTest'],
                         ('v14PreparedAuthorityIsRevokedAndConsumedPreparationRemainsUnchanged',))
        historical = (report.ROOT / report.TEST_ROOT / 'dev/myjarvis/core/StudyLessonMigrationTest.java').read_text()
        self.assertIn('"--spring.flyway.target=14"', historical)
        self.assertIn('.target("15").load().migrate()', historical)
        self.assertIn('assertEquals("14", db.queryForObject', historical)
        self.assertIn('assertEquals("15", db.queryForObject', historical)
        durable = (report.ROOT / report.TEST_ROOT / 'dev/myjarvis/core/DurableStudyMigrationTest.java').read_text()
        self.assertIn('v15PreparationsAreRevokedWhileConsumedLedgerAndUsageRemainExact', durable)
        self.assertIn('migration(ds,"15").migrate()', durable)
        self.assertIn('migration(ds,"16").migrate()', durable)
        self.assertIn('failedV16RollsBackEarlierDdlAndDoesNotRevokeOldAuthority', durable)
        self.assertIn('assertEquals("15",db.queryForObject', durable)
        self.assertIn('assertEquals("16",db.queryForObject', durable)
        self.assertIn('assertEquals(15,expected.size())', durable)
        self.assertIn('/migrations/pre-v16-sha256.properties', durable)

    def test_workflow_keeps_verification_status_and_always_reports(self):
        workflow = (report.ROOT / '.github/workflows/m0.yml').read_text()
        self.assertIn('id: checkout', workflow)
        self.assertIn('REPORT_CHECKOUT_SHA: ${{ steps.checkout.outputs.commit }}', workflow)
        self.assertIn('REPORT_PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}', workflow)
        self.assertIn('run: scripts/check.sh', workflow)
        self.assertIn('if: ${{ always() }}', workflow)
        self.assertIn('VERIFICATION_OUTCOME: ${{ steps.components.outcome }}', workflow)
        self.assertLess(workflow.index('java_test_report.py prepare'), workflow.index('run: scripts/check.sh'))
        self.assertLess(workflow.index('run: scripts/check.sh'), workflow.index('java_test_report.py emit'))
        for forbidden in ('continue-on-error', '|| true', 'upload-artifact', 'cat apps/core/build/test-results', 'secrets.'):
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
