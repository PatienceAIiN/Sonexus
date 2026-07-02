import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { setToken } from "../api/client";

afterEach(() => {
  setToken(null);
  window.localStorage.clear();
});
