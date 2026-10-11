import pathlib
import tempfile
import unittest
from publication_guard import inspect

class PublicationGuardTest(unittest.TestCase):
    def test_safe_file_has_real_digest(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);(root/'README.md').write_text('safe')
            errors,manifest=inspect(root)
            self.assertFalse(errors);self.assertEqual(len(manifest[0]['sha256']),64)

    def test_private_material_rejected(self):
        for name in ['AGENTS.md','prompts/personal.txt','skills/SKILL.md','attachments/input.pdf','.env','secret.key','agents.md','sKiLl.Md','.ClAuDe/settings.json','.cursor/rules/rule.mdc','CLAUDE.md','GEMINI.md','.mcp.json', '.env.example', 'local.env', 'model.lock.json', 'requirements.mac.lock.txt', 'modelpath', 'AIprompts/role.md', 'originalfiles/spec.md', 'weights.safetensors', '01_JARVIS_secure_spec_v4.0.md']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                root=pathlib.Path(d);path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('private')
                self.assertTrue(inspect(root)[0])

    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);(root/'link').symlink_to('/etc/passwd')
            self.assertTrue(inspect(root)[0])

    def test_credential_detection_without_logging_value(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);sentinel='AK'+'IA'+'X'*16;(root/'oops.txt').write_text(sentinel)
            errors,_=inspect(root);self.assertTrue(errors);self.assertNotIn(sentinel,str(errors))

    def test_public_pem_parser_delimiter_is_not_key_material(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);path=root/'Parser.java'
            marker='-----BEGIN '+'PRIVATE KEY-----'
            path.write_text('text.replace("'+marker+'","")')
            self.assertFalse(inspect(root)[0])
            path.write_text(path.read_text()+'\n'+marker+'\n'+'synthetic encoded secret')
            self.assertTrue(inspect(root)[0])

    def test_private_corpus_renamed_and_excerpt_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            base=pathlib.Path(d);root=base/'public';corpus=base/'private'
            root.mkdir();corpus.mkdir()
            synthetic=' '.join('synthetic-private-word-'+str(i) for i in range(100))
            (corpus/'reference.txt').write_text(synthetic)
            (root/'renamed.md').write_text(synthetic)
            errors,_=inspect(root,corpus)
            self.assertTrue(errors);self.assertNotIn(synthetic,str(errors))
            (root/'renamed.md').write_text('prefix\n'+synthetic[128:1100].replace(' ', '\n  ')+'\nsuffix')
            self.assertTrue(inspect(root,corpus)[0])
            (root/'renamed.md').write_text('independent implementation')
            self.assertFalse(inspect(root,corpus)[0])

    def test_configured_corpus_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            self.assertTrue(inspect(root,root/'missing')[0])
            self.assertTrue(inspect(root,root)[0])
            outside=root/'outside';outside.mkdir()
            public=root/'public';public.mkdir()
            self.assertTrue(inspect(public,outside)[0])
            (outside/'ref').symlink_to('/etc/passwd')
            self.assertTrue(inspect(public,outside)[0])

    def test_tracked_ignored_private_name_is_rejected(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            subprocess.run(['git','init','-q',str(root)],check=True)
            (root/'.gitignore').write_text('agents.md\n')
            (root/'agents.md').write_text('synthetic only')
            subprocess.run(['git','add','-f','agents.md'],cwd=root,check=True)
            self.assertTrue(inspect(root)[0])

if __name__=='__main__':unittest.main()
