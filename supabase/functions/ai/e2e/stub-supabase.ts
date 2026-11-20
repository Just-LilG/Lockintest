export const state: any = { rpcs: [] };
export const createClient = () => ({
  rpc: async (name: string, args: any) => {
    state.rpcs.push(name);
    if (name === "ai_key") return { data: args.p_name === "groq" ? "test-key" : "" };
    if (name === "ai_take") return { data: { ok: true, used: 1, limit: 40 }, error: null };
    return { data: null, error: null };
  },
  auth: { getUser: async () => ({ data: { user: { id: "u1" } }, error: null }) },
});
