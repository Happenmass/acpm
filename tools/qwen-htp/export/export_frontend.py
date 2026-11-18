"""Export Qwen mel filters, prompts and byte-level vocabulary for Android."""
from release_paths import SOURCE_DIR, WORK_DIR, MODEL_DIR
import json
from pathlib import Path
import numpy as np
import torch
from transformers import AutoProcessor

root = Path(str(MODEL_DIR))
out = Path(str(WORK_DIR / 'artifacts/frontend'))
out.mkdir(parents=True, exist_ok=True)
torch.set_num_threads(4)
processor = AutoProcessor.from_pretrained(root, local_files_only=True)
np.asarray(processor.feature_extractor.mel_filters, dtype='<f4').tofile(out/'mel_filters.f32')
prompts = {}
for language in ['auto', 'en', 'zh', 'ja', 'pt']:
    inputs = processor.apply_transcription_request(np.zeros(16000, np.float32),
                                                 language=None if language == 'auto' else language)
    ids = inputs['input_ids'][0].tolist()
    first = ids.index(processor.audio_token_id)
    last = len(ids) - list(reversed(ids)).index(processor.audio_token_id)
    assert all(token == processor.audio_token_id for token in ids[first:last])
    prompts[language] = {'prefix': ids[:first], 'suffix': ids[last:]}
    np.asarray(ids[:first],dtype='<i4').tofile(out/f'prompt_{language}_prefix.i32')
    np.asarray(ids[last:],dtype='<i4').tofile(out/f'prompt_{language}_suffix.i32')
    print(language, prompts[language])

# GPT-2 byte alphabet used by Qwen's byte-level BPE decoder.
alphabet = list(range(ord('!'),ord('~')+1)) + list(range(161,173)) + list(range(174,256))
characters = list(alphabet)
extra = 0
for byte in range(256):
    if byte not in alphabet:
        alphabet.append(byte)
        characters.append(256+extra)
        extra += 1
decode_byte = {chr(character): byte for character, byte in zip(characters,alphabet)}
tokenizer = json.loads((root/'tokenizer.json').read_text())
vocabulary = {int(i): token for token,i in tokenizer['model']['vocab'].items()}
special = set()
for token in tokenizer['added_tokens']:
    vocabulary[token['id']] = token['content']
    if token.get('special'): special.add(token['id'])
offsets = [0]
data = bytearray()
for index in range(151936):
    token = vocabulary.get(index, '')
    if index in special:
        encoded = b''
    elif all(character in decode_byte for character in token):
        encoded = bytes(decode_byte[character] for character in token)
    else:
        encoded = token.encode('utf-8')
    data.extend(encoded)
    offsets.append(len(data))
(out/'token_bytes.bin').write_bytes(data)
np.asarray(offsets,dtype='<i4').tofile(out/'token_offsets.i32')

# Verify reconstruction on Chinese, English, punctuation and model output tags.
for text in ['中文与 English 混合。', 'language Chinese<asr_text>你好！', ' a\nb café 🎵']:
    ids = processor.tokenizer.encode(text, add_special_tokens=False)
    actual = b''.join(data[offsets[i]:offsets[i+1]] for i in ids).decode('utf-8')
    expected = processor.tokenizer.decode(ids, skip_special_tokens=True)
    assert actual == expected, (actual,expected)
config = {'sampling_rate':16000, 'n_fft':400, 'hop_length':160, 'num_mel_bins':128,
          'min_samples':8000, 'mel_chunk_frames':100, 'window_tokens':104,
          'audio_token_id':processor.audio_token_id, 'eos_token_ids':[151643,151645],
          'prompts':prompts, 'mel_filter_shape':list(processor.feature_extractor.mel_filters.shape)}
(out/'frontend.json').write_text(json.dumps(config,indent=2))
print(out)
