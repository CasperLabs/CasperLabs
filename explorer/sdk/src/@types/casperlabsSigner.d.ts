interface CasperLabsHelper {
  // whether CasperLabs Sign Helper Plugin is ready
  isConnected: () => boolean;
  // send base16 encoded message to plugin to sign
  sign: (messageBase16: string) => Promise<string>;
  // returns base64 encoded public key of user current selected account.
  getSelectedPublicKeyBase64: () => Promise<string | undefined>;
}

interface Window {
  casperlabsHelper?: CasperLabsHelper
}
