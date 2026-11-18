"""Fixed-shape Qwen decoder block used for HTP export.

The caller supplies RoPE values, attention masks and padded previous KV buffers.
Outputs contain only new KV entries, avoiding full-cache copies back to the CPU.
Both a multi-token prefill block and a one-token decode block use this module.
"""
from release_paths import SOURCE_DIR, WORK_DIR, MODEL_DIR
import torch


def rotate_half(value):
    half = value.shape[-1] // 2
    return torch.cat((-value[..., half:], value[..., :half]), dim=-1)


def repeat_kv(value, repetitions):
    batch, heads, length, width = value.shape
    return value[:, :, None, :, :].expand(batch, heads, repetitions, length, width).reshape(
        batch, heads * repetitions, length, width)


class DecoderBlock(torch.nn.Module):
    def __init__(self, layers):
        super().__init__()
        self.layers = torch.nn.ModuleList(layers)

    def forward(self, hidden, cos, sin, attention_mask, *previous_kv):
        updates = []
        for index, layer in enumerate(self.layers):
            residual = hidden
            x = layer.input_layernorm(hidden)
            attention = layer.self_attn
            shape = (1, hidden.shape[1], -1, attention.head_dim)
            q = attention.q_norm(attention.q_proj(x).reshape(shape)).transpose(1, 2)
            k = attention.k_norm(attention.k_proj(x).reshape(shape)).transpose(1, 2)
            v = attention.v_proj(x).reshape(shape).transpose(1, 2)
            q = q * cos + rotate_half(q) * sin
            k = k * cos + rotate_half(k) * sin
            updates.extend([k, v])
            keys = torch.cat((previous_kv[2*index], k), dim=2)
            values = torch.cat((previous_kv[2*index+1], v), dim=2)
            keys = repeat_kv(keys, attention.num_key_value_groups)
            values = repeat_kv(values, attention.num_key_value_groups)
            probabilities = torch.softmax(torch.matmul(q, keys.transpose(-1, -2)) * attention.scaling
                                          + attention_mask, dim=-1)
            result = torch.matmul(probabilities, values).transpose(1, 2).reshape(1, hidden.shape[1], -1)
            hidden = residual + attention.o_proj(result)
            hidden = hidden + layer.mlp(layer.post_attention_layernorm(hidden))
        return (hidden, *updates)


def causal_mask(valid_previous, sequence_length, capacity):
    """Mask zero-padded cache slots and future positions in the current block."""
    mask = torch.full((1, 1, sequence_length, capacity + sequence_length), -10000.0)
    mask[..., :valid_previous] = 0
    for row in range(sequence_length):
        mask[..., row, capacity:capacity+row+1] = 0
    return mask
