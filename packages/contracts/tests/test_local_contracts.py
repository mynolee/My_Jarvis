import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT=Path(__file__).resolve().parents[1]

def validator(name):
    schema=json.loads((ROOT/'schemas'/f'{name}.schema.json').read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema,format_checker=FormatChecker())

class LocalContractTest(unittest.TestCase):
    def manifest(self):
        # Generated synthetic metadata, never a personal artifact or usable lock.
        return {'complete':True,'revision':'a'*40,'repo':'synthetic/artifact',
                'upstream_repo':'synthetic/source','upstream_revision':'b'*40,
                'upstream_license_sha256':'c'*64,'conversion_license_sha256':'d'*64,
                'licenses_reviewed':True,'runtime_lock_sha256':'e'*64,'prompt_sha256':'f'*64,
                'template_files':['template.jinja'],'tokenizer_files':['tokenizer.json'],
                'processor_files':['processor.json'],
                'files':[{'path':'config.json','size':0,'sha256':'0'*64}]}

    def test_manifest_requires_reviewed_provenance_and_hashes(self):
        v=validator('model-artifact-manifest');base=self.manifest();v.validate(base)
        for field in base:
            item=copy.deepcopy(base);del item[field]
            self.assertFalse(v.is_valid(item),field)
        for change in ({'licenses_reviewed':False},{'revision':'main'},{'upstream_revision':'latest'},
                       {'local_path':'/private/model'},{'training_enabled':True},{'template_files':[]}):
            self.assertFalse(v.is_valid({**base,**change}),change)

    def test_paths_closed_and_bounded(self):
        v=validator('model-artifact-manifest')
        for path in ('../weights.safetensors','/tmp/weights.safetensors','dir/../weights.safetensors',r'dir\weights.safetensors',''):
            item=self.manifest();item['files'][0]['path']=path
            self.assertFalse(v.is_valid(item),path)
        item=self.manifest();item['files'][0]['size']=True
        self.assertFalse(v.is_valid(item))

    def test_readiness_never_conflates_verified_stages(self):
        v=validator('runtime-readiness')
        item={'status':'MODEL_UNAVAILABLE','ready':False,'model_id':'local-mlx-v1','model_manifest_hash':None,
              'download_complete':False,'integrity_verified':False,'runtime_ready':False,
              'inference_verified':False,'integration_verified':False,'control_revision':1}
        v.validate(item)
        self.assertFalse(v.is_valid({**item,'ready':True}))
        self.assertFalse(v.is_valid({**item,'status':'READY'}))
        self.assertFalse(v.is_valid({**item,'integration_verified':True}))
        ready={**item,'status':'READY','ready':True,'runtime_ready':True,'model_manifest_hash':'a'*64,
               'download_complete':True,'integrity_verified':True}
        v.validate(ready)
        self.assertFalse(ready['inference_verified'])

    def test_v4_token_and_model_bounds(self):
        v=validator('turn-request')
        item=json.loads((ROOT/'fixtures/valid/turn-request.json').read_text())
        item.update(model_id='local-mlx-v1',prompt_revision='local-v1');v.validate(item)
        for field,value in [('max_input_tokens',4097),('max_output_tokens',513),('max_compute_ms',60001),('max_model_calls',2)]:
            changed=copy.deepcopy(item);changed['budget'][field]=value
            self.assertFalse(v.is_valid(changed),field)
        self.assertFalse(v.is_valid({**item,'model_id':'remote-ai'}))
        self.assertFalse(v.is_valid({**item,'prompt_revision':'mock-v1'}))
        self.assertFalse(v.is_valid({**item,'model_path':'/private/path'}))

if __name__=='__main__':unittest.main()
