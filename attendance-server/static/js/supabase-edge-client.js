(function () {
  "use strict";

  var ACCESS_TOKEN_KEY = "attendease.supabase.access_token";

  function getSupabaseConfig() {
    var config = window.ATTENDEASE_SUPABASE || {};
    if (!config.FUNCTIONS_URL && config.SUPABASE_URL) {
      config.FUNCTIONS_URL = String(config.SUPABASE_URL).replace(/\/+$/, "") + "/functions/v1";
    }
    if (!config.FUNCTIONS_URL) {
      throw new Error("ATTENDEASE_SUPABASE.FUNCTIONS_URL is not configured.");
    }
    return config;
  }

  function getStoredAccessToken() {
    try {
      return window.localStorage.getItem(ACCESS_TOKEN_KEY) || "";
    } catch (_error) {
      return "";
    }
  }

  function setStoredAccessToken(token) {
    try {
      if (token) {
        window.localStorage.setItem(ACCESS_TOKEN_KEY, token);
      }
    } catch (_error) {
      // Keep the existing UI usable even if storage is blocked.
    }
  }

  function clearStoredAccessToken() {
    try {
      window.localStorage.removeItem(ACCESS_TOKEN_KEY);
    } catch (_error) {
      // Nothing to clear if storage is blocked.
    }
  }

  async function parseJsonResponse(response) {
    var text = await response.text();
    if (!text) return {};
    try {
      return JSON.parse(text);
    } catch (_error) {
      return {
        ok: false,
        error: {
          code: "INVALID_JSON_RESPONSE",
          message: text
        }
      };
    }
  }

  async function callEdgeFunction(functionName, body) {
    var config = getSupabaseConfig();
    var token = getStoredAccessToken();
    var url = String(config.FUNCTIONS_URL).replace(/\/+$/, "") + "/" + encodeURIComponent(functionName);
    var response = await fetch(url, {
      method: "POST",
      headers: {
        "Authorization": token ? "Bearer " + token : "",
        "Content-Type": "application/json"
      },
      body: JSON.stringify(body || {})
    });

    var payload = await parseJsonResponse(response);
    if (!response.ok || payload.ok === false) {
      var edgeError = payload.error || {};
      var error = new Error(edgeError.message || ("Edge Function request failed with HTTP " + response.status));
      error.code = edgeError.code || "EDGE_FUNCTION_ERROR";
      error.status = response.status;
      error.details = edgeError.details || payload;
      throw error;
    }

    return Object.prototype.hasOwnProperty.call(payload, "data") ? payload.data : payload;
  }

  window.AttendEaseSupabaseClient = {
    getSupabaseConfig: getSupabaseConfig,
    getStoredAccessToken: getStoredAccessToken,
    setStoredAccessToken: setStoredAccessToken,
    clearStoredAccessToken: clearStoredAccessToken,
    callEdgeFunction: callEdgeFunction
  };
})();
