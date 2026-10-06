import { createHmac } from 'node:crypto'

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export function base32Decode(encoded: string): Buffer {
  let buffer = 0
  let bits = 0
  const out: number[] = []
  for (const char of encoded.replace(/[\s=]/g, '').toUpperCase()) {
    buffer = (buffer << 5) | ALPHABET.indexOf(char)
    bits += 5
    if (bits >= 8) {
      out.push((buffer >> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(out)
}

/** RFC 6238 (SHA-1, 6 digits, 30 s), matching the backend's Totp. */
export function totp(base32Secret: string, at: number = Date.now()): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)))
  const hash = createHmac('sha1', base32Decode(base32Secret)).update(counter).digest()
  const offset = hash[hash.length - 1] & 0x0f
  const binary =
    ((hash[offset] & 0x7f) << 24) |
    (hash[offset + 1] << 16) |
    (hash[offset + 2] << 8) |
    hash[offset + 3]
  return String(binary % 1_000_000).padStart(6, '0')
}
