import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [command, keyPath, kindArg, expiryArg] = process.argv.slice(2);
const argc = process.argv.length - 2;
const now = () => Math.floor(Date.now() / 1000);
function futureExpiry(value) {
    const expiry = Number(value);
    if (!/^\d+$/.test(value ?? '') || !Number.isSafeInteger(expiry) || expiry <= now()) {
        throw new Error('Expected a future Unix expiry in seconds');
    }
    return expiry;
}
if (!keyPath || !(
    (['keygen', 'public-key'].includes(command) && argc === 2) ||
    (command === 'issue' && (argc === 2 || (argc === 3 && kindArg === 'permanent') || (argc === 4 && kindArg === 'trial'))) ||
    (command === 'cloud-issue' && argc === 3) ||
    (command === 'cloud-set' && argc === 4)
)) {
    console.error('Usage: node scripts/mint-flick.mjs <keygen|public-key|issue> <private-key.pem> [permanent | trial <unix-expiry>]');
    console.error('       node scripts/mint-flick.mjs cloud-issue <card-directory> <unix-expiry>');
    console.error('       node scripts/mint-flick.mjs cloud-set <card-directory> <64-hex-record-id> <unix-expiry|off>');
    process.exit(2);
}

if (command === 'cloud-issue') {
    const expiry = futureExpiry(kindArg);
    const card = 'CPVC1.' + randomBytes(32).toString('base64url');
    const digest = createHash('sha256').update(card).digest('hex');
    writeFileSync(join(keyPath, digest), String(expiry), { flag: 'wx', mode: 0o600 });
    console.error(`Cloud record ID: ${digest}`);
    console.log(card);
} else if (command === 'cloud-set') {
    if (!/^[0-9a-f]{64}$/.test(kindArg)) throw new Error('Expected a 64-hex record ID');
    const target = join(keyPath, kindArg);
    readFileSync(target);
    const expiry = expiryArg === 'off' ? 0 : futureExpiry(expiryArg);
    const temporary = `${target}.${randomUUID()}.tmp`;
    writeFileSync(temporary, String(expiry), { flag: 'wx', mode: 0o600 });
    renameSync(temporary, target);
} else if (command === 'keygen') {
    const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 3072 });
    writeFileSync(keyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { flag: 'wx', mode: 0o600 });
} else {
    const key = createPrivateKey(readFileSync(keyPath));
    if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength < 2048 ||
        key.asymmetricKeyDetails.modulusLength > 8192) {
        throw new Error('Expected a 2048–8192 bit RSA private key');
    }
    if (command === 'public-key') {
        console.log(createPublicKey(key).export({ type: 'spki', format: 'der' }).toString('base64'));
    } else {
        const kind = kindArg ?? 'permanent';
        const expiresAt = kind === 'permanent' ? 0 : futureExpiry(expiryArg);
        const id = randomUUID().replaceAll('-', '');
        const payload = Buffer.from(kind === 'permanent'
            ? `cp.player/flick-vip/v1\n${id}`
            : `cp.player/flick-vip/v2\n${id}\n${kind}\n${expiresAt}`);
        const b64 = (bytes) => bytes.toString('base64').replaceAll('+', '-').replaceAll('/', '_');
        console.error(`License ID: ${id} (${kind}${expiresAt ? `, expires at Unix second ${expiresAt}` : ''})`);
        console.log(`CPV1.${b64(payload)}.${b64(sign('sha256', payload, key))}`);
    }
}
