/** Authenticated public status only. Never render raw errors, paths, or model configuration. */
export interface LocalRuntime {
  mode: 'LOCAL_MLX';
  model_id: 'local-mlx-v1';
  ready: boolean;
  status: 'READY' | 'LOADING' | 'UNAVAILABLE' | 'DISABLED';
}
export function read_runtime(value: unknown): LocalRuntime {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('INVALID_RUNTIME');
  const item = value as Record<string, unknown>;
  if (Object.keys(item).some(key => !['mode', 'model_id', 'ready', 'status'].includes(key)) || typeof item.status !== 'string' || item.mode !== 'LOCAL_MLX' || item.model_id !== 'local-mlx-v1' || typeof item.ready !== 'boolean' || !['READY', 'LOADING', 'UNAVAILABLE', 'DISABLED'].includes(item.status) || item.ready !== (item.status === 'READY')) throw new Error('INVALID_RUNTIME');
  return { mode: 'LOCAL_MLX', model_id: 'local-mlx-v1', ready: item.ready, status: item.status as LocalRuntime['status'] };
}
