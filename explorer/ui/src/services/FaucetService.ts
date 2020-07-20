import AuthService from './AuthService';
import { decodeBase16 } from 'casperlabs-sdk';

/** Call the API on the server backend. */
export default class FaucetService {
  constructor(private authService: AuthService) {}

  async requestTokens(accountPublicKeyHashBase64: string): Promise<DeployHash> {
    const token = await this.authService.getToken();
    const response = await fetch('/api/faucet', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`
      },
      body: JSON.stringify({ accountPublicKeyHashBase64 })
    });
    const json = await response.json();

    if (json.error) {
      throw new Error(json.error);
    }

    return decodeBase16(json.deployHashBase16);
  }
}
