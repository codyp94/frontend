(ns frontend.components.pieces.org-picker
  (:require [frontend.async :refer [raise! navigate!]]
            [frontend.components.common :as common]
            [frontend.components.pieces.tabs :as tabs]
            [frontend.models.user :as user-model]
            [frontend.routes :as routes]
            [frontend.utils :as utils]
            [frontend.utils.bitbucket :as bitbucket]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs :as vcs-utils]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn- vcs-github? [item] (contains? #{"github" nil} (:vcs_type item)))
(defn- vcs-bitbucket? [item] (= "bitbucket" (:vcs_type item)))

(defn- select-vcs-type [vcs-type item]
  (case vcs-type
    "bitbucket" (vcs-bitbucket? item)
    "github"    (vcs-github?    item)))

(defn- missing-org-info
  "A message explaining how to enable organizations which have disallowed CircleCI on GitHub."
  [owner]
  (html
   [:p
    "Are you missing an organization? You or an admin may need to enable CircleCI for your organization in "
    [:a {:href (gh-utils/third-party-app-restrictions-url) :target "_blank"}
     "GitHub's application permissions"]
    ". "
    [:a {:on-click #(raise! owner [:refreshed-user-orgs {}]) ;; TODO: spinner while working?
         :class "active"}
     "Refresh this list"]
    " after you have updated permissions."]))

(defn- organization [org selected? on-click]
  (html
   (let [login (:login org)
         vcs-type (:vcs_type org)]
     [:li {:data-component `organization
           :class (when selected? "active")
           :on-click on-click}
      [:img.avatar {:src (gh-utils/make-avatar-url org :size 50)
                    :height 50}]
      [:.org-name login]
      (if vcs-type
        [:.vcs-icon
         [:a {:href (str (case vcs-type
                           "github" (gh-utils/http-endpoint)
                           "bitbucket" (bitbucket/http-endpoint))
                         "/" login)
              :target "_blank"}
          (case vcs-type
            "github" [:i.octicon.octicon-mark-github]
            "bitbucket" [:i.fa.fa-bitbucket])]])])))

(defn- organization-list [{:keys [orgs selected-org]} owner]
  (reify
    om/IDisplayName (display-name [_] "Organization Listing")
    om/IRender
    (render [_]
      (html
       [:ul {:data-component `organization-list}
        (for [org orgs]
          (let [org-identifier {:login (:login org)
                                :type (if (:org org) :org :user)
                                :vcs-type (:vcs_type org)}
                selected? (= selected-org org-identifier)]
            (organization org selected? #(raise! owner [:selected-add-projects-org org-identifier]))))]))))

(defn- picker-without-bitbucket [{:keys [orgs user selected-org]} owner]
  (reify
    om/IDisplayName (display-name [_] "Organization Listing")
    om/IRender
    (render [_]
      (html
       [:div
        [:h4 "Your accounts"]
        (om/build organization-list {:orgs (filter vcs-github? orgs)
                                     :selected-org selected-org})
        (when (get-in user [:repos-loading :github])
          [:div.orgs-loading
           [:div.loading-spinner common/spinner]])
        (missing-org-info owner)]))))

(defn- picker-with-bitbucket [{:keys [orgs user selected-org tab]} owner]
  (reify
    om/IDisplayName (display-name [_] "Organization Listing")
    om/IRender
    (render [_]
      (let [github-authorized? (user-model/github-authorized? user)
            bitbucket-authorized? (user-model/bitbucket-authorized? user)
            selected-vcs-type (cond
                                tab tab
                                github-authorized? "github"
                                :else "bitbucket")
            github-active? (= "github" selected-vcs-type)
            bitbucket-active? (= "bitbucket" selected-vcs-type)]
        (html
         [:div
          (om/build tabs/tab-row {:tabs [{:name "github"
                                          :icon (html [:i.octicon.octicon-mark-github])
                                          :label "GitHub"}
                                         {:name "bitbucket"
                                          :icon (html [:i.fa.fa-bitbucket])
                                          :label "Bitbucket"}]
                                  :selected-tab-name selected-vcs-type
                                  :on-tab-click #(navigate! owner (routes/v1-add-projects-path {:_fragment %}))})
          [:div.organizations.card
           (when github-active?
             (if github-authorized?
               (missing-org-info owner)
               [:div
                [:p "GitHub is not connected to your account yet. To connect it, click the button below:"]
                [:a.btn.btn-primary {:href (gh-utils/auth-url)
                                     :on-click #((om/get-shared owner :track-event) {:event-type :authorize-vcs-clicked
                                                                                     :properties {:vcs-type selected-vcs-type}})}
                 "Authorize with GitHub"]]))
           (when (and bitbucket-active?
                      (not bitbucket-authorized?))
             [:div
              [:p "Bitbucket is not connected to your account yet. To connect it, click the button below:"]
              [:a.btn.btn-primary {:href (bitbucket/auth-url)
                                   :on-click #((om/get-shared owner :track-event) {:event-type :authorize-vcs-clicked
                                                                                   :properties {:vcs-type selected-vcs-type}})}
               "Authorize with Bitbucket"]])
           (om/build organization-list {:orgs (filter (partial select-vcs-type selected-vcs-type) orgs)
                                        :selected-org selected-org})
           (when (get-in user [:repos-loading (keyword selected-vcs-type)])
             [:div.orgs-loading
              [:div.loading-spinner common/spinner]])]])))))

(defn picker
  "Shows an org picker. The picker will include a Bitbucket tab if Bitbucket is enabled
  for the given user. Accepts the following params:

  :orgs         - The orgs to display.
  :selected-org - The currently selected org.
  :user         - The user whose orgs we're showing, which is to say, the current user. We
                  use the user data to decide whether  Bitbucket is enabled and whether
                  the orgs are still loading and need a spinner.
  :tab          - (optional) The VCS tab to display (\"github\" or \"bitbucket\").
                  Defaults to \"github\"."
  [{:keys [orgs user selected-org tab]} owner]
  (reify
    om/IRender
    (render [_]
      (if (vcs-utils/bitbucket-enabled? user)
        (om/build picker-with-bitbucket {:orgs orgs
                                         :selected-org selected-org
                                         :user user
                                         :tab tab})
        (om/build picker-without-bitbucket {:orgs orgs
                                            :selected-org selected-org
                                            :user user})))))
