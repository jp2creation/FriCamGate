const REDACTED = "[redacted]";

export function redactSensitiveText(input: string): string {
  return input
    .replace(/Authorization:\s*Bearer\s+[A-Za-z0-9_.\-/=]+/gi, `Authorization: Bearer ${REDACTED}`)
    .replace(/Cookie:\s*[^"\n\r]+/gi, `Cookie: ${REDACTED}`)
    .replace(/frigate_token=[^;&\s"']+/gi, `frigate_token=${REDACTED}`)
    .replace(/"?(password|token|authToken|cookie)"?\s*:\s*"[^"]*"/gi, `"$1":"${REDACTED}"`);
}
