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

    def test_missing_method_is_not_observed_not_a_pass(self):
        self.prepare()
        self.xml(suite([testcase()]))
        result = self.emit()
        self.assertEqual(result['status'], 'INCOMPLETE')
        self.assertEqual(result['counts']['passed'], 1)
        self.assertEqual(result['counts']['observed_methods'], 1)
        self.assertGreater(result['counts']['unobserved_methods'], 0)
        self.assertIn('NOT_OBSERVED', {item['outcome'] for item in result['methods']})

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
        self.assertEqual(sum(map(len, report.ALLOWED_TESTS.values())), 437)

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
