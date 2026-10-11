import pathlib
import re
import unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]

class CiPolicyTest(unittest.TestCase):
    def test_each_workflow_is_immutable_and_unprivileged(self):
        workflows=list((ROOT/'.github/workflows').glob('*.yml'))
        self.assertTrue(workflows)
        for path in workflows:
            with self.subTest(workflow=path.name):
                content=path.read_text()
                uses=re.findall(r'uses:\s+(\S+)',content)
                self.assertTrue(uses)
                for action in uses:self.assertRegex(action,r'^[\w/-]+@[0-9a-f]{40}$')
                self.assertRegex(content,r'(?m)^permissions:\n  contents: read$')
                checkouts=sum(action.startswith('actions/checkout@') for action in uses)
                self.assertGreater(checkouts,0)
                self.assertEqual(len(re.findall(r'persist-credentials:\s*false',content)),checkouts)
                for forbidden in ['pull_request_target:', 'secrets.', 'id-token: write', 'contents: write',
                                  'self-hosted', 'workflow_run:', 'printenv', 'set -x', 'env |']:
                    self.assertNotIn(forbidden,content)

    def test_sca_jobs_are_independent_and_fail_closed(self):
        content=(ROOT/'.github/workflows/m0.yml').read_text()
        jobs=re.split(r'(?m)^  (?=[a-z][a-z-]+:\s*$)',content)
        for name in ('python-dependency-audit:', 'web-dependency-audit:'):
            job=next(item for item in jobs if item.startswith(name))
            self.assertNotIn('needs:',job)
            self.assertNotIn('continue-on-error',job)
            self.assertNotIn('|| true',job)
        self.assertIn('uv lock --check',content)
        self.assertIn('uv audit --locked --no-build',content)
        self.assertIn('npm audit --package-lock-only --ignore-scripts --audit-level=high',content)
        self.assertIn('fail-fast: false',content)
        check=(ROOT/'scripts/check.sh').read_text()
        self.assertIn('cd packages/contracts && uv lock --check && uv run --frozen',check)
        self.assertIn('cd apps/intelligence && uv lock --check && PYTHONPATH=src uv run --frozen',check)

if __name__=='__main__':unittest.main()
