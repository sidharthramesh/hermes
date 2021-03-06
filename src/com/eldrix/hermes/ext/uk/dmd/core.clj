; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns com.eldrix.hermes.ext.uk.dmd.core
  "Provides functionality to process and understand data from the
  UK Dictionary of Medicines and Devices (dm+d).

  For more information see
  https://www.nhsbsa.nhs.uk/sites/default/files/2017-02/Technical_Specification_of_data_files_R2_v3.1_May_2015.pdf

  Canonical information about dm+d products is published by the NHS BSA
  in XML files. dm+d components are also released as a SNOMED CT release,
  with identifiers in dm+d being valid SNOMED identifiers. However, the
  SNOMED distribution does not include all necessary drug-related data
  in order to implement e-prescribing.

  This module therefore leverages the basic SNOMED functionality including
  search and store functions (e.g. navigation) but supplements with specific
  dm+d data when available.

  This is, by definition, a UK-only module and will not give expected results for
  drugs outside of the UK Product root.

  See https://www.nhsbsa.nhs.uk/sites/default/files/2020-08/doc_SnomedCTUKDrugExtensionModel%20-%20v1.3_0.pdf

  The dm+d model consists of the following components:
  - VTM
  - VMP
  - VMPP
  - TF
  - AMP
  - AMPP

  The relationships between these components are:
  - VMP <<- IS_A -> VTM
  - VMP <<- HAS_SPECIFIC_ACTIVE_INGREDIENT ->> SUBSTANCE
  - VMP <<- HAS_DISPENSED_DOSE_FORM ->> QUALIFIER
  - VMPP <<- HAS_VMP -> VMP
  - AMPP <<- IS_A -> VMPP
  - AMPP <<- HAS_AMP -> AMP
  - AMP <<- IS_A -> VMP
  - AMP <<- IS_A -> TF
  - AMP <<- HAS_EXCIPIENT ->> QUALIFIER
  - TF <<- HAS_TRADE_FAMILY_GROUP ->> QUALIFIER

  Cardinality rules are: (see https://www.nhsbsa.nhs.uk/sites/default/files/2017-02/Technical_Specification_of_data_files_R2_v3.1_May_2015.pdf)
  The SNOMED dm+d data file documents the cardinality rules for AMP<->TF
  (https://www.nhsbsa.nhs.uk/sites/default/files/2017-04/doc_UKTCSnomedCTUKDrugExtensionEditorialPolicy_Current-en-GB_GB1000001_v7_0.pdf)"
  (:require [clojure.set :as set]
            [com.eldrix.hermes.expression.ecl :as ecl]
            [com.eldrix.hermes.ext.uk.dmd.store :as dmd-store]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as snomed-store]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.impl.language :as lang])
  (:import (com.eldrix.hermes.snomed Concept ExtendedConcept)))

;; Core concepts - types of dm+d product
(def UKProduct 10363601000001109)
(def ActualMedicinalProduct 10363901000001102)
(def ActualMedicinalProductPack 10364001000001104)
(def VirtualMedicinalProduct 10363801000001108)
(def VirtualMedicinalProductPack 8653601000001108)
(def VirtuaTherapeuticMoiety 10363701000001104)
(def TradeFamily 9191801000001103)
(def TradeFamilyGroup 9191901000001109)

;; dm+d reference sets - membership of a reference set tells us which product
(def VtmReferenceSet 999000581000001102)
(def TfReferenceSet 999000631000001100)
(def AmpReferenceSet 999000541000001108)
(def AmppReferenceSet 999000551000001106)
(def VmpReferenceSet 999000561000001109)
(def VmppReferenceSet 999000571000001104)
(def TfgReferenceSet 999000641000001107)

(def refset-id->product
  {VtmReferenceSet  ::vtm
   TfReferenceSet   ::tf
   AmpReferenceSet  ::amp
   AmppReferenceSet ::ampp
   VmpReferenceSet  ::vmp
   VmppReferenceSet ::vmpp
   TfgReferenceSet  ::tfg})

(def product->refset-id
  (set/map-invert refset-id->product))

;;  Language reference sets
(def NhsDmdRealmLanguageReferenceSet 999000671000001103)
(def NhsRealmPharmacyLanguageReferenceSet 999000691000001104)
(def NhsRealmClinicalLanguageReferenceSet 999001261000000100)
(def NhsEPrescribingRouteAdministrationReferenceSet 999000051000001100)
(def DoseFormReferenceSet 999000781000001107)
(def SugarFreeReferenceSet 999000601000001109)
(def GlutenFreeReferenceSet 999000611000001106)
(def PreservativeFreeReferenceSet 999000621000001102)
(def CombinationDrugVtm 999000771000001105)
(def ChlorofluorocarbonFreeReferenceSet 999000651000001105)
(def BlackTriangleReferenceSet 999000661000001108)

(def PendingMove 900000000000492006)
(def HasActiveIngredient 127489000)
(def HasVmp 10362601000001103)
(def HasAmp 10362701000001108)
(def HasTradeFamilyGroup 9191701000001107)
(def HasSpecificActiveIngredient 10362801000001104)
(def HasDispensedDoseForm 10362901000001105)                ;; UK dm+d version of "HasDoseForm"
(def HasDoseForm 411116001)                                 ;; Do not use - from International release - use dm+d relationship instead
(def HasExcipient 8653101000001104)
(def PrescribingStatus 8940001000001105)
(def NonAvailabilityIndicator 8940601000001102)
(def LegalCategory 8941301000001102)
(def DiscontinuedIndicator 8941901000001101)
(def HasBasisOfStrength 10363001000001101)
(def HasUnitOfAdministration 13085501000001109)
(def HasUnitOfPresentation 763032000)
(def HasNHSdmdBasisOfStrength 10363001000001101)
(def HasNHSControlledDrugCategory 13089101000001102)
(def HasVMPNonAvailabilityIndicator 8940601000001102)
(def VMPPrescribingStatus 8940001000001105)
(def HasNHSdmdVmpRouteOfAdministration 13088401000001104)
(def HasNHSdmdVmpOntologyFormAndRoute 13088501000001100)
(def HasPresentationStrengthNumerator 732944001)
(def HasPresentationStrengthDenominator 732946004)
(def HasPresentationStrengthNumeratorUnit 732945000)

(def CautionAMPLevelPrescribingAdvised 13291401000001100)
(def NeverValidToPrescribeAsVrp 12459601000001102)
(def NeverValidToPrescribeAsVmp 8940401000001100)
(def NotRecommendedToPrescribeAsVmp 8940501000001101)
(def InvalidAsPrescribableProduct 8940301000001108)
(def NotRecommendedBrandsNotBioequivalent 9900001000001104)
(def NotRecommendedNoProductSpecification 12468201000001102)
(def NotRecommendedPatientTraining 9900101000001103)
(def VmpValidAsPrescribableProduct 8940201000001104)
(def VrpValidAsPrescribableProduct 12223601000001104)

(defmulti product-type
          "Return the dm+d product type of the concept specified.
          Parameters:
           - store : MapDBStore
          - concept : a concept, either identifier, concept or extended concept."
          (fn [store concept] (class concept)))

(defmethod product-type Long [store concept-id]
  (let [refsets (snomed-store/get-component-refsets store concept-id)]
    (some identity (map refset-id->product refsets))))

(defmethod product-type nil [_ _] nil)

(defmethod product-type Concept [store concept]
  (product-type store (:id concept)))

(defmethod product-type ExtendedConcept [_ extended-concept]
  (some identity (map refset-id->product (:refsets extended-concept))))

(defn is-vtm? [store concept]
  (= ::vtm (product-type store concept)))

(defn is-vmp? [store concept]
  (= ::vmp (product-type store concept)))

(defn is-vmpp? [store concept]
  (= ::vmpp (product-type store concept)))

(defn is-amp? [store concept]
  (= ::amp (product-type store concept)))

(defn is-ampp? [store concept]
  (= ::ampp (product-type store concept)))

(defn is-tf? [store concept]
  (= ::tf (product-type store concept)))

(defn is-tfg? [store concept]
  (= ::tfg (product-type store concept)))

;;
;; Most dm+d logic depends on the product-type, so define
;; polymorphism based on deriving the type.
(defmulti vmps product-type)
(defmulti vtms product-type)
(defmulti amps product-type)
(defmulti ampps product-type)
(defmulti tfs product-type)
(defmulti vmpps product-type)
(defmulti tfgs product-type)

(defmulti specific-active-ingredients product-type)
(defmulti dispensed-dose-forms product-type)
(defmulti non-availability-indicators product-type)
(defmulti prescribing-statuses product-type)
(defmulti basis-of-strength product-type)
(defmulti unit-of-administration product-type)

;; TODO: take advantage of cardinality rules to optimise walking the products
(defmethod vmps ::vtm [store concept-id]
  (filter (partial is-vmp? store) (snomed-store/get-all-children store concept-id)))
(defmethod vtms ::vmp [store concept-id]
  (filter (partial is-vtm? store) (snomed-store/get-all-parents store concept-id)))
(defmethod amps ::vmp [store concept-id]
  (filter (partial is-amp? store) (snomed-store/get-all-children store concept-id)))
(defmethod vmps ::amp [store concept-id]
  (filter (partial is-vmp? store) (snomed-store/get-all-parents store concept-id)))
(defmethod amps ::vtm [store concept-id]
  (filter (partial is-amp? store) (snomed-store/get-all-children store concept-id)))
(defmethod tfs ::amp [store concept-id]
  (filter (partial is-tf? store) (snomed-store/get-all-parents store concept-id)))
(defmethod tfs ::vtm [store concept-id]
  (set (mapcat (partial tfs store) (amps store concept-id))))
(defmethod vmpps ::vmp [store concept-id]
  (filter (partial is-vmpp? store) (snomed-store/get-all-children store concept-id HasVmp)))
(defmethod ampps ::vmpp [store concept-id]
  (filter (partial is-ampp? store) (snomed-store/get-all-children store concept-id)))
(defmethod vmpps ::ampp [store concept-id]
  (filter (partial is-vmpp? store) (snomed-store/get-all-parents store concept-id)))
(defmethod ampps ::amp [store concept-id]
  (filter (partial is-vmpp? store) (snomed-store/get-all-children store concept-id HasAmp)))
(defmethod amps ::ampp [store concept-id]
  (filter (partial is-ampp? store) (snomed-store/get-all-parents store concept-id HasAmp)))
(defmethod tfgs ::tf [store concept-id]
  (filter (partial is-tfg? store) (snomed-store/get-all-parents store concept-id HasTradeFamilyGroup)))
(defmethod tfs ::tfg [store concept-id]
  (filter (partial is-tf? store) (snomed-store/get-all-children store concept-id HasTradeFamilyGroup)))
(defmethod tfgs ::vtm [store concept-id]
  (set (mapcat (partial tfgs store) (tfs store concept-id))))


;;;; core properties for VMPs
(defmethod dispensed-dose-forms ::vmp [store concept-id]
  (snomed-store/get-parent-relationships-of-type store concept-id HasDispensedDoseForm))
(defmethod specific-active-ingredients ::vmp [store concept-id]
  (snomed-store/get-parent-relationships-of-type store concept-id HasSpecificActiveIngredient))
(defmethod prescribing-statuses ::vmp [store concept-id]
  (snomed-store/get-parent-relationships-of-type store concept-id PrescribingStatus))
(defmethod non-availability-indicators ::vmp [store concept-id]
  (snomed-store/get-parent-relationships-of-type store concept-id NonAvailabilityIndicator))
(defmethod basis-of-strength ::vmp [store concept-id]
  (snomed-store/get-parent-relationships-of-type store concept-id HasNHSdmdBasisOfStrength))
(defmethod unit-of-administration ::vmp [store concept-id]
  (snomed-store/get-parent-relationships-of-type store concept-id HasUnitOfAdministration))

;;;; and now derive properties for other product types, if that makes sense

(defmethod prescribing-statuses ::vtm [store concept-id]
  (set (mapcat (partial prescribing-statuses store) (vmps store concept-id))))
(defmethod non-availability-indicators ::vtm [store concept-id]
  (set (mapcat (partial non-availability-indicators store) (vmps store concept-id))))
(defmethod basis-of-strength ::vtm [store concept-id]
  (set (mapcat (partial basis-of-strength store) (vmps store concept-id))))
(defmethod unit-of-administration ::vtm [store concept-id]
  (set (mapcat (partial unit-of-administration store) (vmps store concept-id))))

(defn search
  "Search for a dm+d product by name and type."
  [store searcher s product-type]
  (if-let [refset-id (get product->refset-id product-type)]
    (search/do-search searcher {:s s :query (ecl/parse store searcher (str "^" refset-id))})))

(defn get-dmd-extended-concept [sct-store])

(comment
  (do
    (def store (snomed-store/open-store "snomed.db/store.db"))
    (def index-reader (search/open-index-reader "snomed.db/search.db"))
    (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
    (require '[clojure.pprint :as pp])
    (require '[com.eldrix.hermes.impl.language :as lang])
    (def search-dmd (partial search store searcher))
    (defn fsn [concept-id]
      (:term (snomed-store/get-fully-specified-name store concept-id)))
    (defn ps [concept-id]
      (:term (snomed-store/get-preferred-synonym store concept-id [NhsRealmPharmacyLanguageReferenceSet])))
    (defn pps [concept-id]
      (:term (snomed-store/get-preferred-synonym store concept-id [900000000000508004]))))
  (def amlodipine-vtms (set (map :conceptId (search-dmd "amlodipine" ::vtm))))
  (group-by :conceptId (search-dmd "amlodipine" ::vtm))
  (every? true? (map (partial is-vtm? store) amlodipine-vtms))
  (map :term (map (partial snomed-store/get-fully-specified-name store) amlodipine-vtms))
  (def amlodipine-vtm (first amlodipine-vtms))
  (def amlodipine-vmps (vmps store amlodipine-vtm))
  (every? true? (map (partial is-vmp? store) amlodipine-vmps))
  (map fsn (mapcat (partial vmps store) amlodipine-vtms))
  (def amlodipine-vmp (first (vmps store amlodipine-vtm)))
  (is-vmp? store amlodipine-vmp)
  (not (is-vtm? store amlodipine-vmp))
  (def amlodipine-vmpps (filter (partial is-vmpp? store) (snomed-store/get-all-children store amlodipine-vmp HasVmp)))
  (map fsn amlodipine-vmpps)
  (group-by :conceptId (search-dmd "3,4 diamino" ::vtm))
  (def pyridostigmine-vtm (first (keys (group-by :conceptId (search-dmd "pyridostigmine" ::vtm)))))
  (def pyridostigmine-tfs (tfs store pyridostigmine-vtm))
  (map fsn pyridostigmine-tfs)
  (time (->> (search-dmd "pantoprazole" ::vtm)
             (map :conceptId)
             (mapcat (partial tfs store))
             (distinct)
             (map ps)))

  (search-dmd "bendrofluazide 2.5" ::vmp)
  (vtms store 317919004)
  (map #(hash-map :fsn (fsn %) :id %) (vmps store 91135008))
  (map ps (snomed-store/get-parent-relationships-of-type store 317919004 HasPresentationStrengthNumerator))
  (map ps (snomed-store/get-parent-relationships-of-type store 317919004 HasPresentationStrengthNumeratorUnit))
  (map fsn (snomed-store/get-parent-relationships-of-type store 317919004 HasPresentationStrengthDenominator))

  (search-dmd "sinemet" ::amp)
  (def sinemet-vmp (vmps store 242011000001103))
  (def sinemet-concept (snomed-store/get-concept store 110871000001108))
  (snomed-store/make-extended-concept store sinemet-concept)
  (def dstore (dmd-store/open-dmd-store "dmd3.db"))
  (snomed-store/make-extended-concept store (snomed-store/get-concept store 111841000001109))
  (snomed-store/get-concept store 111841000001109)
  (product-type store 111841000001109)
  (product-type store 319283006)

  (def caf-vmp (first (search-dmd "co-amilofruse 5mg/40mg" ::vmp)))
  (.get (.core dstore) (:conceptId caf-vmp))
  (snomed-store/get-extended-concept store (:conceptId caf-vmp))
  (snomed-store/get-parent-relationships store (:conceptId caf-vmp))
  ;; get precise active ingredients and related information
  (snomed-store/get-grouped-properties store (:conceptId caf-vmp) 762949000)
  (map #(reduce-kv (fn [m k v] (assoc m (fsn k) (pps v))) {} %) (snomed-store/get-grouped-properties store (:conceptId caf-vmp) 762949000))

  (defn parse-active-ingredient
    [m]
    {:preciseActiveIngredient })

  (defn precise-active-ingredients
    [store product-id]
    (let [ingreds (snomed-store/get-grouped-properties store product-id HasSpecificActiveIngredient)]
      )
    )
  )
