(ns dev.arkaitz.web-base.response-test
  "The constructors are exactly the maps the renderer reads: pinned whole,
  because a nil-valued :wb/ key would be invisible to a keyword lookup and
  visible to `render/response`."
  (:require [clojure.test :refer [deftest is]]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.response :as response]
            [ring.mock.request :as mock]))

(def ^:private b [:p "x"])

(deftest ok-and-see-other-are-exactly-the-maps-the-base-reads--absent-options-stay-absent
  (is (= {:status 200 :body [:p "x"]} (response/ok b)) "ok without options is exactly {:status 200 :body b}: no nil-valued :wb/ key")
  (is (= {:status 200 :body [:p "x"]} (response/ok b {})) "an empty option map adds nothing either")
  (is (= {:status 200 :body [:p "x"] :wb/slots {:title "T"}} (response/ok b {:slots {:title "T"}})) "only the option given appears, under its :wb/ name")
  (is (= {:status 200 :body [:p "x"] :wb/height 1} (response/ok b {:height 1})))
  (is (= {:status 200 :body [:p "x"] :wb/height 0 :wb/slots {}} (response/ok b {:height 0 :slots {}})) "0 and {} are values, not absences")
  (is (= {:status 200 :body [:p "x"] :wb/height 1 :wb/slots {:t 1}} (response/ok b {:height 1 :slots {:t 1}})))
  (is (= {:status 303 :headers {"Location" "/x"} :body ""} (response/see-other "/x")) "see-other is a 303 with Location and an empty body"))

(deftest ok-s-keys-are-the-ones-render-reads--height-and-slots-reach-the-layout-stack
  (let [outer   (fn [{:keys [content]}] [:div#outer content])
        titled  (fn [{:keys [content title]}] [:article#titled [:h1 title] content])
        stack   [outer titled]
        page    (mock/request :get "/")
        headers {"Vary" "HX-Request, HX-Request-Type" "Content-Type" "text/html; charset=utf-8"}]
    (is (= {:status 200 :headers headers :body "<article id=\"titled\"><h1>T</h1><p>x</p></article>"}
           (render/response page (response/ok b {:height 1 :slots {:title "T"}}) stack))
        "ok's :height 1 and :slots reach render: one layout, the slot visible, no doctype")
    (is (= {:status 200 :headers headers :body "<!DOCTYPE html>\n<div id=\"outer\"><article id=\"titled\"><h1></h1><p>x</p></article></div>"}
           (render/response page (response/ok b) stack))
        "ok without options leaves render its default: whole stack and doctype")))
