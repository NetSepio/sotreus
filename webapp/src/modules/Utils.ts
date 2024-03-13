export function getBaseUrl(): string {
  const { protocol, host } = window.location;
  return `${protocol}//${host}`;
}
export function getGatewayURL(): string | undefined {
  return process.env.GATEWAY_URL;
}