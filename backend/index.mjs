import http from 'node:http';
import { inflateRawSync } from 'node:zlib';

const PORT = Number(process.env.PORT || 8080);
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
const CLIENT_TOKEN = process.env.MOSAIC_CLIENT_TOKEN || '';
const DEFAULT_MODEL = 'gemini-3.7-flash';
const MAX_BODY = 12 * 1024 * 1024;
const rate = new Map();

function send(res, code, data) {
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(data));
}

function safeTextFromZip(buffer) {
  const chunks = [];
  let total = 0;
  const allowed = /\.(txt|md|csv|json|xml|html|css|js|ts|kt|java|py|yml|yaml|properties|gradle|toml|log|ini|sql|sh)$/i;
  const maxEntry = 180_000;
  const maxTotal = 750_000;

  // Locate the ZIP end-of-central-directory record (including a variable comment).
  const minEocd = Math.max(0, buffer.length - 65_557);
  let eocd = -1;
  for (let i = buffer.length - 22; i >= minEocd; i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0 || eocd + 22 > buffer.length) throw new Error('Invalid ZIP directory');
  const entryCount = buffer.readUInt16LE(eocd + 10);
  const directorySize = buffer.readUInt32LE(eocd + 12);
  let cursor = buffer.readUInt32LE(eocd + 16);
  if (cursor + directorySize > buffer.length) throw new Error('Truncated ZIP directory');

  for (let index = 0; index < entryCount; index++) {
    if (cursor + 46 > buffer.length || buffer.readUInt32LE(cursor) !== 0x02014b50) break;
    const flags = buffer.readUInt16LE(cursor + 8);
    const method = buffer.readUInt16LE(cursor + 10);
    const compressedSize = buffer.readUInt32LE(cursor + 20);
    const declaredSize = buffer.readUInt32LE(cursor + 24);
    const nameLength = buffer.readUInt16LE(cursor + 28);
    const extraLength = buffer.readUInt16LE(cursor + 30);
    const commentLength = buffer.readUInt16LE(cursor + 32);
    const localOffset = buffer.readUInt32LE(cursor + 42);
    const nameStart = cursor + 46;
    const nameEnd = nameStart + nameLength;
    const next = nameEnd + extraLength + commentLength;
    if (next > buffer.length) break;
    const entryName = buffer.subarray(nameStart, nameEnd).toString((flags & 0x0800) ? 'utf8' : 'utf8');
    cursor = next;

    if (!entryName || entryName.endsWith('/') || (flags & 1) !== 0) continue;
    const normalized = entryName.replace(/\\/g, '/');
    if (normalized.split('/').some(part => !part || part === '.' || part === '..' || part.startsWith('.'))) continue;
    if (!allowed.test(normalized)) continue;
    if (declaredSize === 0 || declaredSize > maxEntry || total + declaredSize > maxTotal) continue;
    if (compressedSize === 0xffffffff || declaredSize === 0xffffffff || localOffset + 30 > buffer.length) continue;
    if (method !== 0 && method !== 8) continue; // stored or DEFLATE only
    if (buffer.readUInt32LE(localOffset) !== 0x04034b50) continue;

    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const dataEnd = dataStart + compressedSize;
    if (dataStart < 0 || dataEnd > buffer.length || dataEnd < dataStart) continue;

    let bytes;
    try {
      const compressed = buffer.subarray(dataStart, dataEnd);
      bytes = method === 0 ? Buffer.from(compressed) : inflateRawSync(compressed, { maxOutputLength: maxEntry });
    } catch { continue; }
    if (bytes.length > maxEntry || bytes.length !== declaredSize) continue;
    const text = bytes.toString('utf8').replace(/\u0000/g, '').slice(0, 80_000);
    const part = `\n\n--- ${normalized} ---\n${text}`;
    if (total + part.length > 650_000) break;
    chunks.push(part);
    total += part.length;
  }
  return chunks.join('') || 'The ZIP contained no supported text files. It may contain only binary assets.';
}

async function handleGenerate(req, res) {
  if (!GEMINI_API_KEY) return send(res, 503, { error: 'Server is missing GEMINI_API_KEY.' });
  if (!CLIENT_TOKEN) return send(res, 503, { error: 'Server is missing MOSAIC_CLIENT_TOKEN.' });
  const auth = req.headers.authorization || '';
  const supplied = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (!timingSafeEqual(supplied, CLIENT_TOKEN)) return send(res, 401, { error: 'Invalid backend token.' });

  const ip = req.headers['x-forwarded-for']?.toString().split(',')[0]?.trim() || req.socket.remoteAddress || 'unknown';
  const now = Date.now();
  const bucket = rate.get(ip) || { start: now, count: 0 };
  if (now - bucket.start > 10 * 60 * 1000) { bucket.start = now; bucket.count = 0; }
  bucket.count += 1; rate.set(ip, bucket);
  if (bucket.count > 40) return send(res, 429, { error: 'Rate limit reached. Try again later.' });

  let raw = '';
  try {
    for await (const chunk of req) {
      raw += chunk.toString('utf8');
      if (Buffer.byteLength(raw, 'utf8') > MAX_BODY) return send(res, 413, { error: 'Request is too large. Maximum payload is 12 MB.' });
    }
    const body = JSON.parse(raw || '{}');
    const userText = String(body.text || '').slice(0, 30_000);
    const model = String(body.model || DEFAULT_MODEL);
    if (!/^gemini-[a-zA-Z0-9.-]+$/.test(model)) return send(res, 400, { error: 'Invalid model name.' });

    const parts = [{ text: userText || 'Please inspect the attached file and summarize its contents.' }];
    if (body.fileBase64) {
      const fileName = String(body.fileName || 'attachment').slice(0, 180);
      const mime = String(body.mimeType || 'application/octet-stream').toLowerCase();
      const bytes = Buffer.from(String(body.fileBase64), 'base64');
      if (bytes.length > 8 * 1024 * 1024) return send(res, 413, { error: 'Attachment exceeds 8 MB.' });
      const ext = fileName.split('.').pop().toLowerCase();
      if (ext === 'zip' || ['docx', 'xlsx', 'pptx'].includes(ext)) {
        let extracted;
        try { extracted = safeTextFromZip(bytes); }
        catch { return send(res, 400, { error: 'Could not read this archive.' }); }
        parts.push({ text: `Contents extracted from ${fileName}:\n${extracted}` });
      } else if (mime.startsWith('image/') || mime === 'application/pdf') {
        parts.push({ inline_data: { mime_type: mime, data: bytes.toString('base64') } });
      } else if (mime.startsWith('text/') || /\.(txt|md|csv|json|xml|html|css|js|ts|kt|java|py|yml|yaml|log|ini|sql|sh)$/i.test(fileName)) {
        parts.push({ text: `File ${fileName}:\n${bytes.toString('utf8').slice(0, 600_000)}` });
      } else {
        return send(res, 415, { error: 'Supported attachments: images, PDF, text/code files, ZIP, DOCX, XLSX, and PPTX.' });
      }
    }

    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': GEMINI_API_KEY },
      body: JSON.stringify({
        system_instruction: { parts: [{ text: 'You are MOSAIC Assistant. Be helpful, clear, concise, and answer in the user\'s language. When analyzing an attachment, distinguish visible facts from assumptions.' }] },
        contents: [{ role: 'user', parts }],
        generationConfig: { temperature: 0.65, maxOutputTokens: 4096 }
      })
    });
    const result = await response.json();
    if (!response.ok) {
      const message = result?.error?.message || `Gemini API returned HTTP ${response.status}`;
      return send(res, response.status, { error: message });
    }
    const output = (result?.candidates?.[0]?.content?.parts || []).map(part => part.text || '').join('').trim();
    if (!output) return send(res, 502, { error: 'Gemini returned no text.' });
    return send(res, 200, { text: output, model });
  } catch (error) {
    return send(res, 400, { error: error?.message || 'Invalid request.' });
  }
}

function timingSafeEqual(a, b) {
  const aa = Buffer.from(a); const bb = Buffer.from(b);
  if (aa.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
  return diff === 0;
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true, model: DEFAULT_MODEL });
  if (req.method === 'POST' && req.url === '/api/generate') return handleGenerate(req, res);
  return send(res, 404, { error: 'Not found.' });
});
server.listen(PORT, '0.0.0.0', () => console.log(`MOSAIC AI proxy listening on ${PORT}`));
