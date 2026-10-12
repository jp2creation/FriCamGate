import { describe, expect, it } from "vitest";
import { redactSensitiveText } from "./redact";

describe("redactSensitiveText", () => {
  it("redacts auth headers, cookies, tokens, and passwords", () => {
    expect(redactSensitiveText("Authorization: Bearer " + "abc.def.ghi")).not.toContain("abc.def.ghi");
    expect(redactSensitiveText("Cookie: " + "frigate" + "_token=secret")).not.toContain("secret");
    expect(redactSensitiveText('{"password":"plain"}')).not.toContain("plain");
  });
});
