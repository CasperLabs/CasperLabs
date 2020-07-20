import { expect } from 'chai';
import { decodeBase16 } from '../../src';
import { Ed25519 } from '../../src/lib/Keys';
import { byteHash } from '../../src/lib/Contracts';

describe('Ed25519', () => {

  it('calculates the public key hash', () => {
    const signKeyPair = Ed25519.newKeyPair();
    const name = Buffer.from('ED25519');
    const sep = decodeBase16('00');
    const bytes = Buffer.concat([name, sep, signKeyPair.publicKey]);
    const hash = byteHash(bytes);

    expect(Ed25519.publicKeyHash(signKeyPair.publicKey)).deep.equal(hash);
  });
});

