import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "./App";

describe("App", () => {
  it("renders the FriCamGate setup screen", () => {
    render(<App />);

    expect(screen.getByRole("heading", { name: /FriCamGate/i })).toBeInTheDocument();
    expect(screen.getByAltText(/FriCamGate logo/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/server url/i)).toHaveValue("");
    expect(screen.getByRole("button", { name: /test connection/i })).toBeDisabled();
  });
});
