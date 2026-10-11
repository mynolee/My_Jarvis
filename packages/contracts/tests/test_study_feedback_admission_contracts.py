"""Static source gates only. They are not runtime database/process evidence."""
import ast
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
INTEL=ROOT/'apps/intelligence/src/jarvis_intelligence'


class FeedbackAdmissionSourceTests(unittest.TestCase):
    def source(self,name):return (INTEL/(name+'.py')).read_text()

    def test_app_acquires_original_lease_before_ledger_and_attaches_same_object(self):
        source=self.source('app');start=source.index('def create_app(');source=source[start:]
        self.assertLess(source.index('OwnershipLease.acquire('),source.index('ledger = Ledger('))
        self.assertIn('ownership_lease=lease',source);self.assertIn('local.attach_control_ledger(ledger, ownership_lease=lease)',source)
        self.assertIn("@app.get('/health/study-feedback')",source)

    def test_feedback_runtime_has_dedicated_worker_source_and_single_template_attempt(self):
        runtime=self.source('local_runtime');adapter=self.source('mlx_adapter')
        self.assertIn("worker_request['feedback_source'] = turn.feedback_source",runtime)
        self.assertIn("if feedback:",runtime);self.assertIn('range(1 if feedback else 29)',adapter)
        self.assertIn("if feedback or request.get('context') is None",adapter)
        self.assertIn("{'feedback_source': request['feedback_source']}",adapter)
        self.assertEqual(adapter.count('stream = self.stream('),1)

    def test_migration_has_transactional_rebuild_row_witness_and_strict_schema_gate(self):
        ledger=self.source('ledger')
        self.assertIn('original ownership lease required for upgrade',ledger)
        self.assertIn('ledger schema fingerprint mismatch',ledger)
        self.assertIn('unexpected inbound ledger foreign key',ledger)
        self.assertIn('upgrade final row witness mismatch',ledger)
        self.assertIn('PRAGMA foreign_key_check',ledger)
        self.assertIn('after_final_validation',ledger)
        self.assertNotIn('foreign_keys=OFF',ledger);self.assertNotIn('writable_schema',ledger)
        self.assertIn('if row != (2, self.ledger_instance_id)',ledger)

    def test_all_new_production_python_is_syntactically_valid_without_execution(self):
        for name in ('app','contracts','ledger','local_runtime','mlx_adapter'):
            with self.subTest(name=name):ast.parse(self.source(name))

    def test_no_embedded_private_template_or_profile_personalization(self):
        source=self.source('local_runtime')+self.source('mlx_adapter')
        self.assertIn('study_feedback_prompt_path',source)
        self.assertIn('load_study_feedback_prompt(config)',source)
        self.assertNotIn('display_name',source);self.assertNotIn('learning_style',source)
        self.assertIn('repr=False',self.source('local_runtime'))
