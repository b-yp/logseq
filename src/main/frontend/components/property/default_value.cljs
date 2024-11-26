(ns frontend.components.property.default-value
  (:require [rum.core :as rum]
            [frontend.components.property.value :as pv]
            [frontend.db :as db]))

(rum/defc default-value-config
  [property]
  (let [default-value (:logseq.property/default-value property)]
    (pv/property-value property
                       (db/entity :logseq.property/default-value)
                       default-value {})))