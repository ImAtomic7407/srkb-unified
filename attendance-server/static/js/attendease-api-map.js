(function () {
  "use strict";

  function client() {
    if (!window.AttendEaseSupabaseClient) {
      throw new Error("AttendEaseSupabaseClient is not loaded.");
    }
    return window.AttendEaseSupabaseClient;
  }

  function call(functionName, body) {
    return client().callEdgeFunction(functionName, body);
  }

  function bootstrap() {
    return call("me", { action: "bootstrap" });
  }

  function listCentres(status) {
    return call("centre-admin", {
      action: "list_centres",
      status: status || "active"
    });
  }

  function getBatchSessionContext(centreId, batchId) {
    var body = {
      action: "get_batch_session_context",
      centre_id: centreId
    };
    if (batchId) body.batch_id = batchId;
    return call("batch-session", body);
  }

  function getLookupBundle(centreId) {
    return call("lookup-admin", {
      action: "get_lookup_bundle",
      centre_id: centreId
    });
  }

  function lookupByMobile(centreId, mobile) {
    return call("people-roster", {
      action: "lookup_by_mobile",
      centre_id: centreId,
      mobile: mobile
    });
  }

  function registerPerson(payload) {
    return call("people-roster", Object.assign({ action: "register_person" }, payload || {}));
  }

  function checkSessionUser(payload) {
    return call("attendance-mutate", Object.assign({ action: "check_session_user" }, payload || {}));
  }

  function markAttendance(payload) {
    return call("attendance-mutate", Object.assign({ action: "mark_attendance" }, payload || {}));
  }

  function listLiveAttendance(payload) {
    return call("attendance-mutate", Object.assign({ action: "list_live_attendance" }, payload || {}));
  }

  function liveCount(payload) {
    return call("attendance-mutate", Object.assign({ action: "live_count" }, payload || {}));
  }

  window.AttendEaseApi = {
    bootstrap: bootstrap,
    listCentres: listCentres,
    getBatchSessionContext: getBatchSessionContext,
    getLookupBundle: getLookupBundle,
    lookupByMobile: lookupByMobile,
    registerPerson: registerPerson,
    checkSessionUser: checkSessionUser,
    markAttendance: markAttendance,
    listLiveAttendance: listLiveAttendance,
    liveCount: liveCount
  };
})();
