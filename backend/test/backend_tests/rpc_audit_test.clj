;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-audit-test
  (:require
   [app.common.pprint :as pp]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.request]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn decode-row
  [{:keys [props context] :as row}]
  (cond-> row
    (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props))
    (db/pgobject? context) (assoc :context (db/decode-transit-pgobject context))))

(def http-request
  (reify
    yetti.request/IRequest
    (get-header [_ name]
      (case name
        "x-forwarded-for" "127.0.0.44"
        "x-real-ip" "127.0.0.43"))))

(t/deftest push-events-1
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id (str proj-id)
                                     :team-id (str team-id)
                                     :route "dashboard-files"}
                             :context {:engine "blink"}
                             :profile-id (:id prof)
                             :timestamp (ct/now)
                             :type "action"}]}

          params  (with-meta params
                    {:app.http/request http-request})

          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows)))
        (t/is (= (:id prof) (:profile-id row)))
        (t/is (= "navigate" (:name row)))
        (t/is (= "frontend" (:source row)))))))

(t/deftest push-events-2
  (with-redefs [app.config/flags #{:audit-log}]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id (str proj-id)
                                     :team-id (str team-id)
                                     :route "dashboard-files"}
                             :context {:engine "blink"}
                             :profile-id uuid/zero
                             :timestamp (ct/now)
                             :type "action"}]}
          params  (with-meta params
                    {:app.http/request http-request})
          out     (th/command! params)]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        ;; (pp/pprint rows)
        (t/is (= 1 (count rows)))
        (t/is (= (:id prof) (:profile-id row)))
        (t/is (= "navigate" (:name row)))
        (t/is (= "frontend" (:source row)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TELEMETRY MODE (frontend ingest)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest push-events-telemetry-mode-stores-anonymized-row
  ;; When telemetry is enabled and audit-log is NOT, frontend events
  ;; must be stored with source="telemetry", empty props, zeroed ip,
  ;; and context filtered to safe keys only.
  (with-redefs [cf/flags              #{:telemetry}
                cf/telemetry-enabled? true]
    (let [prof    (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id prof)
          proj-id (:default-project-id prof)

          params  {::th/type :push-audit-events
                   ::rpc/profile-id (:id prof)
                   :events [{:name "navigate"
                             :props {:project-id (str proj-id)
                                     :team-id (str team-id)
                                     :route "dashboard-files"}
                             :context {:browser "Chrome"
                                       :browser-version "120.0"
                                       :os "Linux"
                                       :version "2.0.0"
                                       :session "should-be-stripped"
                                       :external-session-id "also-stripped"
                                       :initiator "app"}
                             :timestamp (ct/now)
                             :type "action"}]}

          params  (with-meta params
                    {:app.http/request http-request})
          out     (th/command! params)]

      (t/is (nil? (:error out)))
      (t/is (nil? (:result out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        (t/is (= 1 (count rows)))
        ;; source is telemetry:frontend, not frontend
        (t/is (= "telemetry:frontend" (:source row)))
        ;; profile-id preserved
        (t/is (= (:id prof) (:profile-id row)))
        ;; event name preserved
        (t/is (= "navigate" (:name row)))
        ;; props only contain UUID-valued keys (non-UUID stripped)
        (t/is (= {} (:props row)))
        ;; ip zeroed
        (t/is (= "0.0.0.0" (str (:ip-addr row))))
        ;; timestamps truncated to day precision
        (let [day-now (ct/truncate (ct/now) :days)]
          (t/is (= day-now (:created-at row)))
          (t/is (= day-now (:tracked-at row))))
        ;; context only contains safe keys
        (let [ctx (:context row)]
          (t/is (contains? ctx :browser))
          (t/is (= "Chrome" (:browser ctx)))
          (t/is (contains? ctx :os))
          (t/is (= "Linux" (:os ctx)))
          ;; session-linking keys stripped
          (t/is (not (contains? ctx :session)))
          (t/is (not (contains? ctx :external-session-id))))))))

(t/deftest push-events-audit-log-flag-prevents-telemetry-rows
  ;; When the :audit-log flag is active, telemetry-mode storage must
  ;; NOT happen — events go to full audit log instead.
  (with-redefs [cf/flags              #{:audit-log :telemetry}
                cf/telemetry-enabled? true]
    (let [prof   (th/create-profile* 1 {:is-active true})
          params {::th/type :push-audit-events
                  ::rpc/profile-id (:id prof)
                  :events [{:name "navigate"
                            :props {:route "dashboard"}
                            :context {:browser "Chrome"}
                            :timestamp (ct/now)
                            :type "action"}]}
          params (with-meta params
                   {:app.http/request http-request})
          out    (th/command! params)]

      (t/is (nil? (:error out)))

      (let [[row :as rows] (->> (th/db-exec! ["select * from audit_log"])
                                (mapv decode-row))]
        (t/is (= 1 (count rows)))
        ;; Stored as full audit-log entry, not telemetry
        (t/is (= "frontend" (:source row)))
        ;; Props preserved (not stripped)
        (t/is (contains? (:props row) :route))
        ;; IP preserved (not zeroed)
        (t/is (not= "0.0.0.0" (str (:ip-addr row))))))))

(t/deftest push-events-disabled-when-no-flags-and-no-telemetry
  ;; When neither audit-log nor telemetry is enabled, no rows should
  ;; be stored.
  (with-redefs [cf/flags              #{}
                cf/telemetry-enabled? false]
    (let [prof   (th/create-profile* 1 {:is-active true})
          params {::th/type :push-audit-events
                  ::rpc/profile-id (:id prof)
                  :events [{:name "navigate"
                            :props {:route "dashboard"}
                            :timestamp (ct/now)
                            :type "action"}]}
          params (with-meta params
                   {:app.http/request http-request})
          out    (th/command! params)]

      (t/is (nil? (:error out)))
      (t/is (= 0 (count (th/db-exec! ["select * from audit_log"])))))))
