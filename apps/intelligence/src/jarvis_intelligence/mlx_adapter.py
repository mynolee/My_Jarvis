"""Pinned MLX-VLM 0.7.6 text adapter; importable on Linux without importing MLX.

API source: upstream v0.7.6 utils.py, prompt_utils.py and
mlx_vlm/generate/dispatch.py. Installed signatures were inspected separately;
source inspection is not a successful Metal inference test.
"""
from __future__ import annotations

import json
import time
from .contracts import CONTRACT_ROOT, canonical, parse_json
from .local_runtime import RuntimeFailure


def output_schema(allowed_tools):
    schema = json.loads((CONTRACT_ROOT / 'schemas/turn-response.schema.json').read_text())
    variants = []
    for branch in schema['oneOf']:
        kind = branch['properties']['kind']['const']
        if kind == 'ERROR' or (kind == 'TOOL_PROPOSALS' and not allowed_tools):
            continue
        branch['properties'].pop('usage')
        branch['required'].remove('usage')
        variants.append(branch)
    return {'oneOf': variants}


def admit(input_ids, max_input, max_output):
    """Count the actual tensor passed to generation; always reserve 512 output."""
    if len(input_ids.shape) != 2 or input_ids.shape[0] != 1:
        raise RuntimeFailure('CONTEXT_LIMIT')
    count = int(input_ids.shape[1])
    if type(max_output) is not int or not 1 <= max_output <= 512:
        raise RuntimeFailure('CONTEXT_LIMIT')
    if count < 1 or count > max_input or count + 512 > 4096:
        raise RuntimeFailure('CONTEXT_LIMIT')
    return count


class MLXAdapter:
    def __init__(self, config):
        import mlx.core as mx
        from mlx_vlm import load, stream_generate
        from mlx_vlm.prompt_utils import apply_chat_template
        from mlx_vlm.utils import prepare_inputs, should_add_special_tokens
        if not mx.metal.is_available():
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        # Configuration is pinned by the private manifest, never model output.
        self.config = json.loads((config.model_path / 'config.json').read_text())
        if self.config.get('model_type') != 'qwen3_5' or self.config.get('architectures') != ['Qwen3_5ForConditionalGeneration'] or self.config.get('text_config', {}).get('model_type') != 'qwen3_5_text':
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        quant = self.config.get('quantization', {})
        if quant.get('bits') != 4 or quant.get('group_size') != 64 or quant.get('mode', 'affine') != 'affine':
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        self.model, self.processor = load(str(config.model_path), adapter_path=None, lazy=False,
                                         strict=True, trust_remote_code=False, local_files_only=True)
        self.system = config.prompt_path.read_text()
        if not self.system or len(self.system.encode()) > 65536:
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        self.mx = mx
        self.render = apply_chat_template
        self.prepare = prepare_inputs
        self.special = should_add_special_tokens
        self.stream = stream_generate

    def generate(self, request, cancel):
        messages = [{'role': 'system', 'content': self.system + '\n' + canonical(output_schema(request['allowed_tool_ids'])).decode() + '\n' + canonical({'current_time': request['current_time'], 'timezone': request['timezone']}).decode()},
                    {'role': 'user', 'content': request['user_input']}]
        prompt = self.render(self.processor, self.config, messages,
                             add_generation_prompt=True, tokenize=False, enable_thinking=False)
        if not isinstance(prompt, str):
            raise RuntimeFailure('MODEL_UNAVAILABLE')
        inputs = self.prepare(self.processor, prompts=prompt, images=None, audio=None, videos=None,
                              image_token_index=getattr(self.model.config, 'image_token_index', None),
                              add_special_tokens=self.special(self.model.config.model_type, self.processor))
        input_ids = inputs['input_ids']
        count = admit(input_ids, request['max_input_tokens'], request['max_output_tokens'])
        if cancel.is_set():
            raise RuntimeFailure('CANCELLED')
        # Pass the SAME tensors that were admitted. No second tokenizer path.
        kwargs = {k: v for k, v in inputs.items() if k not in ('input_ids', 'attention_mask', 'pixel_values')}
        self.mx.reset_peak_memory()
        start = time.monotonic()
        stream = self.stream(self.model, self.processor, prompt, input_ids=input_ids,
                             mask=inputs.get('attention_mask'), pixel_values=inputs.get('pixel_values'),
                             max_tokens=request['max_output_tokens'], temperature=0.0, **kwargs)
        parts, output_tokens, finish = [], 0, None
        try:
            for result in stream:
                if cancel.is_set():
                    raise RuntimeFailure('CANCELLED')
                if time.monotonic() - start > 60:
                    raise RuntimeFailure('GENERATION_TIMEOUT')
                # generation_tokens includes hidden/control tokens and EOS.
                output_tokens = int(result.generation_tokens)
                if output_tokens > request['max_output_tokens']:
                    raise RuntimeFailure('MODEL_OUTPUT_INVALID')
                parts.append(result.text)
                if sum(len(part.encode()) for part in parts) > 65536:
                    raise RuntimeFailure('MODEL_OUTPUT_INVALID')
                finish = result.finish_reason
        finally:
            stream.close()
        if cancel.is_set():
            raise RuntimeFailure('CANCELLED')
        text = ''.join(parts)
        try:
            output = parse_json(text.encode(), limit=65536)
        except ValueError:
            raise RuntimeFailure('MODEL_OUTPUT_INVALID') from None
        return {'output': output, 'input_tokens': count, 'output_tokens': output_tokens,
                'compute_ms': int((time.monotonic() - start) * 1000),
                'peak_memory_bytes': int(self.mx.get_peak_memory()), 'finish_reason': finish or 'stop'}
