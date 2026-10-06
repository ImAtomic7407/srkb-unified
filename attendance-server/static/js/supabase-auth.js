(function () {
  "use strict";

  var BOOTSTRAP_KEY = "attendease.bootstrap";
  var client = null;

  function getConfig() {
    return window.ATTENDEASE_SUPABASE || null;
  }

  function getConfigError() {
    var config = getConfig();
    if (!config) {
      return "Supabase config missing. Create static/js/supabase-config.js from supabase-config.example.js.";
    }
    if (!config.SUPABASE_URL || !config.SUPABASE_ANON_KEY) {
      return "Supabase config incomplete. Set SUPABASE_URL and SUPABASE_ANON_KEY in static/js/supabase-config.js.";
    }
    return "";
  }

  function initSupabaseClient() {
    if (client) return client;
    var error = getConfigError();
    if (error) throw new Error(error);
    if (!window.supabase || !window.supabase.createClient) {
      throw new Error("Supabase JS failed to load.");
    }
    var config = getConfig();
    client = window.supabase.createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY);
    return client;
  }

  async function getSession() {
    var supabaseClient = initSupabaseClient();
    var result = await supabaseClient.auth.getSession();
    if (result.error) throw result.error;
    return result.data ? result.data.session : null;
  }

  async function getAccessToken() {
    var session = await getSession();
    return session && session.access_token ? session.access_token : "";
  }

  function storeBootstrap(data) {
    try {
      window.localStorage.setItem(BOOTSTRAP_KEY, JSON.stringify(data || {}));
    } catch (_error) {
      // Auth still works if storage is unavailable.
    }
  }

  function readStoredBootstrap() {
    try {
      var raw = window.localStorage.getItem(BOOTSTRAP_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (_error) {
      return null;
    }
  }

  function clearStoredBootstrap() {
    try {
      window.localStorage.removeItem(BOOTSTRAP_KEY);
    } catch (_error) {
      // Nothing to clear.
    }
  }

  function setAdapterToken(token) {
    if (window.AttendEaseSupabaseClient && window.AttendEaseSupabaseClient.setStoredAccessToken) {
      window.AttendEaseSupabaseClient.setStoredAccessToken(token);
    }
  }

  function clearAdapterToken() {
    if (window.AttendEaseSupabaseClient && window.AttendEaseSupabaseClient.clearStoredAccessToken) {
      window.AttendEaseSupabaseClient.clearStoredAccessToken();
    }
  }

  async function bootstrap() {
    var token = await getAccessToken();
    if (!token) throw new Error("No active Supabase session.");
    setAdapterToken(token);
    if (!window.AttendEaseApi || !window.AttendEaseApi.bootstrap) {
      throw new Error("AttendEaseApi bootstrap helper is not loaded.");
    }
    var data = await window.AttendEaseApi.bootstrap();
    storeBootstrap(data);
    renderAuthStatus(data);
    return data;
  }

  async function signIn(email, password) {
    var supabaseClient = initSupabaseClient();
    var result = await supabaseClient.auth.signInWithPassword({
      email: email,
      password: password
    });
    if (result.error) throw result.error;
    var token = result.data && result.data.session ? result.data.session.access_token : "";
    if (token) setAdapterToken(token);
    var data = await bootstrap();
    routeByBootstrap(data);
    return data;
  }

  async function signOut() {
    try {
      initSupabaseClient();
      if (client) await client.auth.signOut();
    } catch (_error) {
      // Still clear local state and leave the page.
    }
    clearAdapterToken();
    clearStoredBootstrap();
    window.location.href = "/login";
  }

  function getUser(data) {
    return data && data.user ? data.user : {};
  }

  function getRouting(data) {
    return data && data.routing ? data.routing : {};
  }

  function isAdminLike(data) {
    var user = getUser(data);
    var caps = data && data.capabilities ? data.capabilities : {};
    return Boolean(
      caps.is_super_admin ||
      caps.is_admin ||
      caps.can_manage_global ||
      user.global_role === "super_admin" ||
      user.global_role === "admin"
    );
  }

  function hasCentreAdminCapability(data) {
    var centres = data && data.capabilities && data.capabilities.centres
      ? data.capabilities.centres
      : {};
    return Object.keys(centres).some(function (centreId) {
      return centres[centreId] && centres[centreId].can_manage;
    });
  }

  function routeByBootstrap(data) {
    var routing = getRouting(data);
    var user = getUser(data);
    if (routing.should_show_disabled || user.status === "disabled") {
      window.location.href = "/login?disabled=1";
      return;
    }
    if (routing.should_show_waiting_approval || user.status === "pending") {
      window.location.href = "/waiting_approval";
      return;
    }
    if (routing.default_screen === "super_admin" || routing.default_screen === "admin" || isAdminLike(data) || hasCentreAdminCapability(data)) {
      window.location.href = "/";
      return;
    }
    window.location.href = "/register";
  }

  function showPageMessage(message, isError) {
    var target = document.getElementById("auth-status-message") || document.getElementById("errorMsg");
    if (!target) return;
    target.textContent = message;
    target.style.display = "block";
    target.classList.remove("hidden");
    if (isError) target.classList.add("auth-error");
  }

  async function requireBootstrap(options) {
    options = options || {};
    try {
      var data = await bootstrap();
      var user = getUser(data);
      var routing = getRouting(data);

      if (routing.should_show_disabled || user.status === "disabled") {
        window.location.href = "/login?disabled=1";
        return null;
      }
      if (routing.should_show_waiting_approval || user.status === "pending") {
        window.location.href = "/waiting_approval";
        return null;
      }
      if (options.requireAdmin && !(isAdminLike(data) || hasCentreAdminCapability(data))) {
        showPageMessage("You do not have permission to open this admin page.", true);
        window.setTimeout(function () { window.location.href = "/register"; }, 1200);
        return null;
      }
      return data;
    } catch (error) {
      showPageMessage(error.message || "Authentication required.", true);
      if (
        (error.message || "").indexOf("Supabase config") >= 0 ||
        (error.message || "").indexOf("Supabase JS failed") >= 0 ||
        (error.message || "").indexOf("AttendEaseApi") >= 0
      ) {
        return null;
      }
      window.setTimeout(function () { window.location.href = "/login"; }, 800);
      return null;
    }
  }

  function renderAuthStatus(data) {
    data = data || readStoredBootstrap();
    if (!data) return;
    var user = getUser(data);
    var roleEl = document.getElementById("auth-user-role");
    var emailEl = document.getElementById("auth-user-email");
    var statusEl = document.getElementById("auth-user-status");
    if (roleEl) roleEl.textContent = user.global_role || "";
    if (emailEl) emailEl.textContent = user.email || "";
    if (statusEl) statusEl.textContent = user.status || "";
  }

  function attachLogoutHandlers() {
    document.addEventListener("click", function (event) {
      var target = event.target && event.target.closest ? event.target.closest("[data-supabase-logout]") : null;
      if (!target) return;
      event.preventDefault();
      signOut();
    });
  }

  window.AttendEaseAuth = {
    initSupabaseClient: initSupabaseClient,
    signIn: signIn,
    signOut: signOut,
    getSession: getSession,
    getAccessToken: getAccessToken,
    bootstrap: bootstrap,
    routeByBootstrap: routeByBootstrap,
    requireBootstrap: requireBootstrap,
    renderAuthStatus: renderAuthStatus,
    getConfigError: getConfigError,
    attachLogoutHandlers: attachLogoutHandlers
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", attachLogoutHandlers);
  } else {
    attachLogoutHandlers();
  }
})();
