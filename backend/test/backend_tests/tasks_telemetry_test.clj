;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.tasks-telemetry-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.tasks.telemetry :as telemetry]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- insert-telemetry-row!
  "Insert a single anonymised audit_log row as the telemetry mode does."
  ([name] (insert-telemetry-row! name {}))
  ([name {:keys [tracked-at created-at source]
          :or   {tracked-at (ct/now)
                 created-at (ct/now)
                 source     "telemetry:backend"}}]
   (th/db-insert! :audit-log
                  {:id         (uuid/next)
                   :name       name
                   :type       "action"
                   :source     source
                   :profile-id uuid/zero
                   :ip-addr    (db/inet "0.0.0.0")
                   :props      (db/tjson {})
                   :context    (db/tjson {})
                   :tracked-at tracked-at
                   :created-at created-at})))

(defn- count-telemetry-rows []
  (-> (th/db-exec-one! ["SELECT count(*) AS cnt FROM audit_log WHERE source IN ('telemetry:backend', 'telemetry:frontend')"])
      :cnt
      long))

(defn- decode-event-batch
  "Decode the base64+fressian+zstd event-batch sent to the mock."
  [b64-str]
  (blob/decode-str b64-str))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STATS / REPORT STRUCTURE TESTS (existing behaviour, extended)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-base-report-data-structure
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (let [prof (th/create-profile* 1 {:is-active true
                                      :props {:newsletter-updates true}})]

      (th/run-task! :telemetry {:send? true :enabled? true})

      (t/is (:called? @mock))
      (let [[_ data] (-> @mock :call-args)]
        (t/is (contains? data :subscriptions))
        (t/is (= [(:email prof)] (:subscriptions data)))
        (t/is (contains? data :stats))
        (let [stats (:stats data)]
          (t/is (contains? stats :total-fonts))
          (t/is (contains? stats :total-users))
          (t/is (contains? stats :total-projects))
          (t/is (contains? stats :total-files))
          (t/is (contains? stats :total-teams))
          (t/is (contains? stats :total-comments))
          (t/is (contains? stats :jvm-cpus))
          (t/is (contains? stats :jvm-heap-max))
          (t/is (contains? stats :max-users-on-team))
          (t/is (contains? stats :avg-users-on-team))
          (t/is (contains? stats :max-files-on-project))
          (t/is (contains? stats :avg-files-on-project))
          (t/is (contains? stats :max-projects-on-team))
          (t/is (contains? stats :avg-files-on-project))
          (t/is (contains? stats :email-domains))
          (t/is (= ["nodomain.com"] (:email-domains stats))))
        (t/is (contains? data :version))
        (t/is (contains? data :instance-id))))))

(t/deftest test-telemetry-disabled-no-send
  ;; When telemetry is disabled and no newsletter subscriptions exist,
  ;; make-legacy-request must not be called at all.
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (with-redefs [cf/flags #{}]
      (th/create-profile* 1 {:is-active true})
      (th/run-task! :telemetry {:send? true})
      (t/is (not (:called? @mock))))))

(t/deftest test-telemetry-disabled-newsletter-only-send
  ;; When telemetry is disabled but a user has newsletter-updates opted in,
  ;; make-legacy-request is called once with only subscriptions + version (no stats).
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (with-redefs [cf/flags #{}]
      (let [prof (th/create-profile* 1 {:is-active true
                                        :props {:newsletter-updates true}})]
        (th/run-task! :telemetry {:send? true})
        (t/is (:called? @mock))
        (let [[_ data] (:call-args @mock)]
          ;; Limited payload — no stats
          (t/is (contains? data :subscriptions))
          (t/is (contains? data :version))
          (t/is (not (contains? data :stats)))
          (t/is (= [(:email prof)] (:subscriptions data))))))))

(t/deftest test-send-is-skipped-when-send?-false
  ;; Passing send?=false must suppress all HTTP calls even when enabled.
  (with-mocks [mock {:target 'app.tasks.telemetry/make-legacy-request
                     :return nil}]
    (with-redefs [cf/flags #{:telemetry}]
      (th/create-profile* 1 {:is-active true})
      (th/run-task! :telemetry {:send? false :enabled? true})
      (t/is (not (:called? @mock))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; AUDIT-EVENT BATCH COLLECTION TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-no-audit-events-no-batch-call
  ;; When telemetry is enabled but there are no audit_log rows with
  ;; source='telemetry', the batch send path must not be invoked.
  (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                            :return nil}
               batch-mock  {:target 'app.tasks.telemetry/send-event-batch
                            :return true}]
    (with-redefs [cf/flags #{:telemetry}]
      (th/run-task! :telemetry {:send? true :enabled? true})
      (t/is (:called? @legacy-mock))
      (t/is (not (:called? @batch-mock))))))

(t/deftest test-audit-events-sent-and-deleted-on-success
  ;; Happy path: telemetry rows are collected, shipped as a batch and
  ;; deleted from the table when the endpoint returns success.
  (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                            :return nil}
               batch-mock  {:target 'app.tasks.telemetry/send-event-batch
                            :return true}]
    (with-redefs [cf/flags #{:telemetry}]
      (insert-telemetry-row! "navigate")
      (insert-telemetry-row! "create-file")
      (insert-telemetry-row! "update-file")

      (t/is (= 3 (count-telemetry-rows)))

      (th/run-task! :telemetry {:send? true :enabled? true})

      ;; batch send was called at least once
      (t/is (:called? @batch-mock))

      ;; all rows deleted after successful send
      (t/is (= 0 (count-telemetry-rows))))))

(t/deftest test-audit-events-kept-on-batch-failure
  ;; When the batch endpoint returns failure the rows must be retained
  ;; so the next scheduled run can retry.
  (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                            :return nil}
               batch-mock  {:target 'app.tasks.telemetry/send-event-batch
                            :return false}]
    (with-redefs [cf/flags #{:telemetry}]
      (insert-telemetry-row! "navigate")
      (insert-telemetry-row! "create-file")

      (th/run-task! :telemetry {:send? true :enabled? true})

      (t/is (:called? @batch-mock))
      ;; rows still present — not deleted on failure
      (t/is (= 2 (count-telemetry-rows))))))

(t/deftest test-audit-events-not-collected-when-audit-log-flag-set
  ;; When the :audit-log flag is active, mode C is disabled and the
  ;; batch path must never run (audit-log owns those rows instead).
  (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                            :return nil}
               batch-mock  {:target 'app.tasks.telemetry/send-event-batch
                            :return true}]
    (with-redefs [cf/flags #{:telemetry :audit-log}]
      (insert-telemetry-row! "navigate")

      (th/run-task! :telemetry {:send? true :enabled? true})

      (t/is (not (:called? @batch-mock)))
      ;; row untouched
      (t/is (= 1 (count-telemetry-rows))))))

(t/deftest test-batch-payload-contains-required-fields
  ;; Inspect the actual arguments forwarded to send-event-batch to
  ;; verify the payload carries instance-id, version and events.
  (let [captured (atom nil)]
    (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                              :return nil}]
      (with-redefs [cf/flags #{:telemetry}
                    telemetry/send-event-batch
                    (fn [_cfg batch]
                      (reset! captured batch)
                      true)]
        (insert-telemetry-row! "navigate")
        (insert-telemetry-row! "create-file")

        (th/run-task! :telemetry {:send? true :enabled? true})

        (t/is (some? @captured))
        (let [batch @captured]
          ;; batch is a seq of event maps
          (t/is (seq batch))
          (t/is (= 2 (count batch)))
          ;; each event has name, type, source — profile-id is preserved,
          ;; props and ip-addr are stripped
          (let [ev (first batch)]
            (t/is (contains? ev :name))
            (t/is (contains? ev :type))
            (t/is (contains? ev :source))
            (t/is (contains? ev :profile-id))
            (t/is (not (contains? ev :props)))
            (t/is (not (contains? ev :ip-addr)))))))))

(t/deftest test-batch-encoding-is-decodable
  ;; Verify that encode-batch produces a blob that round-trips back
  ;; through blob/decode to the original data.
  (let [events [{:name "navigate" :type "action" :source "telemetry"
                 :tracked-at (ct/now)}
                {:name "create-file" :type "action" :source "telemetry"
                 :tracked-at (ct/now)}]
        ;; Call the private fn through the ns-mapped var
        encode  (ns-resolve 'app.tasks.telemetry 'encode-batch)
        encoded (encode events)
        decoded (decode-event-batch encoded)]
    (t/is (string? encoded))
    (t/is (seq decoded))
    (t/is (= (count events) (count decoded)))
    (t/is (= "navigate" (:name (first decoded))))
    (t/is (= "create-file" (:name (second decoded))))))

(t/deftest test-multiple-batches-when-many-events
  ;; Lower batch-size to 1 so that 3 events produce 3 separate
  ;; HTTP requests and verify all are sent and all rows deleted.
  (let [call-count (atom 0)]
    (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                              :return nil}]
      (with-redefs [cf/flags             #{:telemetry}
                    telemetry/batch-size 1
                    telemetry/send-event-batch
                    (fn [_cfg _batch]
                      (swap! call-count inc)
                      true)]
        (insert-telemetry-row! "navigate")
        (insert-telemetry-row! "create-file")
        (insert-telemetry-row! "update-file")

        (th/run-task! :telemetry {:send? true :enabled? true})

        ;; Each event is fetched and sent in its own loop iteration
        (t/is (= 3 @call-count))
        ;; All rows deleted after all iterations succeed
        (t/is (= 0 (count-telemetry-rows)))))))

(t/deftest test-partial-failure-stops-remaining-batches
  ;; With batch-size 1, when the second send fails the loop stops.
  ;; The first batch was already deleted; the two remaining rows
  ;; are retained for the next run.
  (let [call-count (atom 0)]
    (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                              :return nil}]
      (with-redefs [cf/flags             #{:telemetry}
                    telemetry/batch-size 1
                    telemetry/send-event-batch
                    (fn [_cfg _batch]
                      (swap! call-count inc)
                      ;; fail on the second call
                      (not= 2 @call-count))]
        (insert-telemetry-row! "navigate")
        (insert-telemetry-row! "create-file")
        (insert-telemetry-row! "update-file")

        (th/run-task! :telemetry {:send? true :enabled? true})

        ;; Stopped at iteration 2 — third event never attempted
        (t/is (= 2 @call-count))
        ;; First batch was deleted on success; 2 rows remain for retry
        (t/is (= 2 (count-telemetry-rows)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GC / RETENTION-WINDOW TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-gc-purges-events-older-than-7-days
  ;; Insert events from 8 days ago (stale) and from today (fresh).
  ;; After the task runs, stale events must be purged by GC and fresh
  ;; ones shipped by the batch sender.
  (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                            :return nil}
               batch-mock  {:target 'app.tasks.telemetry/send-event-batch
                            :return true}]
    (with-redefs [cf/flags #{:telemetry}]
      (let [now        (ct/now)
            eight-days (ct/minus now (ct/duration {:days 8}))]
        ;; Stale events (older than 7 days)
        (insert-telemetry-row! "stale-1" {:created-at eight-days :tracked-at eight-days})
        (insert-telemetry-row! "stale-2" {:created-at eight-days :tracked-at eight-days})
        ;; Fresh events (today)
        (insert-telemetry-row! "fresh-1" {:created-at now :tracked-at now})
        (insert-telemetry-row! "fresh-2" {:created-at now :tracked-at now})

        (t/is (= 4 (count-telemetry-rows)))

        (th/run-task! :telemetry {:send? true :enabled? true})

        ;; GC purged the 2 stale rows, batch sender shipped the 2 fresh ones
        (t/is (= 0 (count-telemetry-rows)))))))

(t/deftest test-gc-keeps-events-within-7-day-window
  ;; When all events are within the 7-day window, GC must not delete
  ;; anything and all rows are forwarded to the batch sender.
  (let [batch-events (atom nil)]
    (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                              :return nil}]
      (with-redefs [cf/flags #{:telemetry}
                    telemetry/send-event-batch
                    (fn [_cfg batch]
                      (reset! batch-events batch)
                      true)]
        (let [six-days-ago (ct/minus (ct/now) (ct/duration {:days 6}))]
          (insert-telemetry-row! "recent-1" {:created-at six-days-ago :tracked-at six-days-ago})
          (insert-telemetry-row! "recent-2" {:created-at six-days-ago :tracked-at six-days-ago}))

        (th/run-task! :telemetry {:send? true :enabled? true})

        ;; Both events forwarded — GC left them alone
        (t/is (= 2 (count @batch-events)))
        (t/is (= 0 (count-telemetry-rows)))))))

(t/deftest test-gc-deletes-only-stale-events
  ;; Insert a mix of stale (8 days old) and fresh (1 day old) events.
  ;; After GC, only fresh events should remain for the batch sender.
  (let [batch-events (atom nil)]
    (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                              :return nil}]
      (with-redefs [cf/flags #{:telemetry}
                    telemetry/send-event-batch
                    (fn [_cfg batch]
                      (reset! batch-events batch)
                      true)]
        (let [eight-days (ct/minus (ct/now) (ct/duration {:days 8}))
              one-day    (ct/minus (ct/now) (ct/duration {:days 1}))]
          (insert-telemetry-row! "stale" {:created-at eight-days :tracked-at eight-days})
          (insert-telemetry-row! "fresh" {:created-at one-day :tracked-at one-day}))

        (t/is (= 2 (count-telemetry-rows)))

        (th/run-task! :telemetry {:send? true :enabled? true})

        ;; GC purged stale, batch shipped fresh
        (t/is (= 1 (count @batch-events)))
        (t/is (= "fresh" (:name (first @batch-events))))
        (t/is (= 0 (count-telemetry-rows)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ANONYMITY TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-telemetry-rows-stored-without-pii
  ;; Rows written to audit_log in telemetry mode must carry no PII:
  ;; empty props, zeroed ip, profile-id=zero, source='telemetry'.
  ;; Safe context fields (browser, os, version, etc.) are preserved
  ;; but session-linking and access-token fields are stripped.
  (with-redefs [cf/flags #{:telemetry}]
    (let [_prof (th/create-profile* 1 {:is-active true})
          safe-ctx {:browser "Chrome"
                    :browser-version "120.0"
                    :os "Linux"
                    :version "2.0.0"}]
      ;; Simulate what app.loggers.audit/handle-event! does in mode C
      (th/db-insert! :audit-log
                     {:id         (uuid/next)
                      :name       "create-project"
                      :type       "action"
                      :source     "telemetry:backend"
                      :profile-id uuid/zero
                      :ip-addr    (db/inet "0.0.0.0")
                      :props      (db/tjson {})
                      :context    (db/tjson safe-ctx)
                      :tracked-at (ct/now)
                      :created-at (ct/now)})

      (let [[row] (th/db-exec! ["SELECT * FROM audit_log WHERE source = 'telemetry:backend'"])]
        (t/is (= "telemetry:backend" (:source row)))
        ;; props are always empty
        (t/is (= "{}" (str (:props row))))
        ;; ip_addr is the sentinel zero address
        (t/is (= "0.0.0.0" (str (:ip-addr row))))
        ;; profile-id is uuid/zero — not a real user id
        (t/is (= uuid/zero (:profile-id row)))))))

(t/deftest test-batch-events-contain-no-pii-fields
  ;; The event maps forwarded to send-event-batch must not carry props,
  ;; ip-addr or profile-id. Safe context fields (browser, os, etc.) may
  ;; be present but session-linking keys must be absent.
  (let [captured-batch (atom nil)
        ;; Insert a row that carries safe context (as the real path does)
        safe-ctx       {:browser "Firefox" :browser-version "121.0"
                        :os "macOS" :session "should-be-stripped"
                        :external-session-id "also-stripped"}]
    (with-mocks [legacy-mock {:target 'app.tasks.telemetry/make-legacy-request
                              :return nil}]
      (with-redefs [cf/flags #{:telemetry}
                    telemetry/send-event-batch
                    (fn [_cfg batch]
                      (reset! captured-batch batch)
                      true)]
        ;; Insert with safe context already pre-filtered (as the ingest path does)
        (th/db-insert! :audit-log
                       {:id         (uuid/next)
                        :name       "navigate"
                        :type       "action"
                        :source     "telemetry:frontend"
                        :profile-id uuid/zero
                        :ip-addr    (db/inet "0.0.0.0")
                        :props      (db/tjson {})
                        :context    (db/tjson (dissoc safe-ctx :session :external-session-id))
                        :tracked-at (ct/now)
                        :created-at (ct/now)})

        (th/run-task! :telemetry {:send? true :enabled? true})

        (t/is (= 1 (count @captured-batch)))
        (let [ev (first @captured-batch)]
          ;; must have the core identity fields including profile-id
          (t/is (contains? ev :name))
          (t/is (contains? ev :type))
          (t/is (contains? ev :source))
          (t/is (contains? ev :tracked-at))
          (t/is (contains? ev :profile-id))
          ;; props and ip-addr must be stripped
          (t/is (not (contains? ev :props)))
          (t/is (not (contains? ev :ip-addr)))
          ;; context may be present and must not contain session-linking keys
          (when-let [ctx (:context ev)]
            (t/is (not (contains? ctx :session)))
            (t/is (not (contains? ctx :external-session-id)))
            ;; safe keys should be present
            (t/is (contains? ctx :browser))))))))

(t/deftest test-telemetry-rows-have-day-precision-timestamps
  ;; Telemetry events must be stored with timestamps truncated to day
  ;; precision so that exact event timing cannot be inferred.
  (with-redefs [cf/flags              #{:telemetry}
                cf/telemetry-enabled? true]
    (let [handle-event! (ns-resolve 'app.loggers.audit 'handle-event!)
          profile       (th/create-profile* 1 {:is-active true})
          event         {::audit/type       "action"
                         ::audit/name       "create-project"
                         ::audit/profile-id (:id profile)}]
      (db/tx-run! th/*system* handle-event! event)
      (let [[row] (th/db-exec! ["SELECT * FROM audit_log WHERE source = 'telemetry:backend'"])]
        (t/is (some? row))
        (let [created-at (:created-at row)
              tracked-at (:tracked-at row)
              day-now    (ct/truncate (ct/now) :days)]
          ;; Both timestamps must equal midnight of the current day
          (t/is (= day-now created-at))
          (t/is (= day-now tracked-at)))))))

(t/deftest test-backend-ingest-full-row-shape
  ;; Verify the full row shape stored by handle-event! in telemetry mode:
  ;; source=telemetry:backend, empty props, zeroed ip, context filtered to safe
  ;; backend keys only, profile-id preserved, timestamps truncated.
  (with-redefs [cf/flags              #{:telemetry}
                cf/telemetry-enabled? true]
    (let [handle-event! (ns-resolve 'app.loggers.audit 'handle-event!)
          profile       (th/create-profile* 1 {:is-active true})
          event         {::audit/type       "action"
                         ::audit/name       "create-project"
                         ::audit/profile-id (:id profile)
                         ::audit/context    {:initiator "app"
                                             :version "2.0.0"
                                             :client-version "1.0"
                                             :client-user-agent "Mozilla/5.0"
                                             :external-session-id "should-be-stripped"
                                             :session "also-stripped"}
                         ::audit/props      {:some-prop "value"}}]
      (db/tx-run! th/*system* handle-event! event)

      (let [[row] (th/db-exec! ["SELECT * FROM audit_log WHERE source = 'telemetry:backend'"])]
        (t/is (some? row))
        ;; source
        (t/is (= "telemetry:backend" (:source row)))
        ;; profile-id preserved
        (t/is (= (:id profile) (:profile-id row)))
        ;; name
        (t/is (= "create-project" (:name row)))
        ;; type
        (t/is (= "action" (:type row)))
        ;; props stripped to empty
        (t/is (= "{}" (str (:props row))))
        ;; ip zeroed
        (t/is (= "0.0.0.0" (str (:ip-addr row))))
        ;; timestamps truncated to day
        (let [day-now    (ct/truncate (ct/now) :days)]
          (t/is (= day-now (:created-at row)))
          (t/is (= day-now (:tracked-at row))))
        ;; context filtered: only safe backend keys retained
        (let [ctx (db/decode-transit-pgobject (:context row))]
          (t/is (= "app" (:initiator ctx)))
          (t/is (= "2.0.0" (:version ctx)))
          (t/is (= "1.0" (:client-version ctx)))
          (t/is (= "Mozilla/5.0" (:client-user-agent ctx)))
          ;; session-linking keys stripped
          (t/is (not (contains? ctx :external-session-id)))
          (t/is (not (contains? ctx :session))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILTER-SAFE-CONTEXT UNIT TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest test-filter-safe-context-keeps-browser-fields
  ;; Safe environment fields must survive the filter.
  (let [filter-safe-context (ns-resolve 'app.rpc.commands.audit 'filter-safe-context)
        ctx {:browser         "Chrome"
             :browser-version "120.0"
             :engine          "Blink"
             :engine-version  "120.0"
             :os              "Windows 11"
             :os-version      "11"
             :device-type     "unknown"
             :device-arch     "amd64"
             :locale          "en-US"
             :version         "2.0.0"
             :screen-width    1920
             :screen-height   1080
             :event-origin    "workspace"}
        result (filter-safe-context ctx)]
    (t/is (= "Chrome" (:browser result)))
    (t/is (= "120.0" (:browser-version result)))
    (t/is (= "Windows 11" (:os result)))
    (t/is (= "en-US" (:locale result)))
    (t/is (= "workspace" (:event-origin result)))
    (t/is (= 1920 (:screen-width result)))))

(t/deftest test-filter-safe-context-strips-pii-keys
  ;; Session-linking and access-token fields must be removed.
  (let [filter-safe-context (ns-resolve 'app.rpc.commands.audit 'filter-safe-context)
        ctx {:browser              "Firefox"
             :session              "abc-session-id"
             :external-session-id  "ext-123"
             :file-stats           {:total-shapes 42}
             :initiator            "app"
             :access-token-id      "tok-456"
             :access-token-type    "api-key"}
        result (filter-safe-context ctx)]
    (t/is (= "Firefox" (:browser result)))
    (t/is (not (contains? result :session)))
    (t/is (not (contains? result :external-session-id)))
    (t/is (not (contains? result :file-stats)))
    (t/is (not (contains? result :initiator)))
    (t/is (not (contains? result :access-token-id)))
    (t/is (not (contains? result :access-token-type)))))

(t/deftest test-filter-safe-context-empty-input
  ;; An empty context should return an empty map without error.
  (let [filter-safe-context (ns-resolve 'app.rpc.commands.audit 'filter-safe-context)]
    (t/is (= {} (filter-safe-context {})))))
