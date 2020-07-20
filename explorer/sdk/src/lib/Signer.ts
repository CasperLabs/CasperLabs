/**
 * Provide methods to communicate with [CasperLabs Plugin](https://github.com/CasperLabs/signer).
 * Works only on Browser.
 */

/**
 * whether CasperLabs Sign Helper Plugin is ready
 */
export const isConnected: () => boolean = () => {
  return !!window?.casperlabsHelper?.isConnected();
};

/**
 * returns base64 encoded public key of user current selected account.
 * @throws Error if haven't connected to CasperLabs Signer browser plugin.
 */
export const getSelectedPublicKeyBase64: () => Promise<string | undefined> = () => {
  throwIfNotConnected();
  return window.casperlabsHelper!.getSelectedPublicKeyBase64();
};

/**
 * send base16 encoded message to plugin to sign
 * @throws Error if haven't connected to CasperLabs Signer browser plugin.
 */
export const sign: (messageBase16: string) => Promise<string> = (messageBase16: string) => {
  throwIfNotConnected();
  return window.casperlabsHelper!.sign(messageBase16);
};

const throwIfNotConnected = () => {
  if (!isConnected()) {
    throw new Error('No CasperLabs Signer browser plugin detected or it is not ready');
  }
};
