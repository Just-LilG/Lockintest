/** Maths in the model's JSON replies: LaTeX is full of backslashes, and JSON treats some of them as escapes. */

// \n followed by one of these is LaTeX (\nu, \neq, \nabla...), not a line break.
const NEWLINE_LATEX = /^(?:u|eq|abla|otin|ewline|eg|e)(?![A-Za-z])/;

/**
 * Makes a raw model reply parseable without losing LaTeX. Models often write \frac with ONE backslash inside a JSON
 * string: \f is a valid JSON escape (form feed) so it would silently turn into a control character, and \s, \p, \a...
 * are invalid and make the whole reply unparseable. Any backslash that is not a real JSON escape is doubled. \b \f \r
 * \t followed by a letter are never real escapes in a reply like this (\theta, \frac, \right, \beta), so they are doubled
 * too; \n is doubled only when a LaTeX command that starts with n follows.
 */
export function repairJsonBackslashes(raw: string): string {
  return raw.replace(/\\(u[0-9a-fA-F]{4}|[\s\S])/g, (all, rest: string, offset: number) => {
    if (rest[0] === "u" && rest.length === 5) return all;
    if (rest === "\\" || rest === '"' || rest === "/") return all;
    const next = raw.slice(offset + 2);
    if ("bfrt".includes(rest)) return /^[A-Za-z]/.test(next) ? "\\\\" + rest : all;
    if (rest === "n") return NEWLINE_LATEX.test(next) ? "\\\\n" : all;
    return "\\\\" + rest;
  });
}

const CONTROL: Record<string, string> = { "\b": "\\b", "\f": "\\f", "\t": "\\t", "\r": "\\r" };

/** A string that was already parsed with a LaTeX command swallowed by an escape (formfeed + "rac"): put it back. */
export function unmangleMath(s: string): string {
  return s
    .replace(/[\b\f\t\r](?=[A-Za-z])/g, (c) => CONTROL[c])
    .replace(/\n(?=(?:u|eq|abla|otin|ewline|eg)(?![A-Za-z]))/g, "\\n");
}

/** Keeps a model's maths from breaking the app: unbalanced $ pairs are left alone (the app reads a lone $ as money). */
export function cleanMathText(v: unknown, max: number): string {
  let s = unmangleMath(String(v ?? "")).replace(/\u0000/g, "").trim();
  if (s.length > max) {
    s = s.slice(0, max);
    // never cut inside a $…$ (an open formula would swallow the rest of the screen)
    const dollars = (s.match(/(?<!\\)\$/g) ?? []).length;
    if (dollars % 2 === 1) s = s.slice(0, s.lastIndexOf("$")).trim();
  }
  return s;
}
