import { afterEach, describe, expect, it, vi } from "vitest";

import { browser, csrfHeaders, resetForTests, safeReturnTo, signIn, SIGN_IN_URL } from "./session";

describe("safeReturnTo", () => {
  it("only allows paths on this site", () => {
    expect(safeReturnTo("/tickets/1001")).toBe("/tickets/1001");
    expect(safeReturnTo("//evil.test/phish")).toBe("/");
    expect(safeReturnTo("https://evil.test")).toBe("/");
    expect(safeReturnTo("/\\evil.test")).toBe("/");
    expect(safeReturnTo(null)).toBe("/");
  });
});

describe("csrfHeaders", () => {
  afterEach(() => {
    document.cookie = "HELPDESK-XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("copies the CSRF cookie into the header the API checks", () => {
    document.cookie = "HELPDESK-XSRF-TOKEN=abc%3D123";
    expect(csrfHeaders()).toEqual({ "X-XSRF-TOKEN": "abc=123" });
  });

  it("sends nothing when there is no cookie yet", () => {
    expect(csrfHeaders()).toEqual({});
  });
});

describe("signIn", () => {
  afterEach(() => {
    resetForTests();
    vi.restoreAllMocks();
  });

  it("redirects once, however many requests fail at the same time", () => {
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    signIn("/tickets/7");
    signIn("/tickets/8");
    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith(SIGN_IN_URL);
    expect(sessionStorage.getItem("helpdesk.returnTo")).toBe("/tickets/7");
  });
});
