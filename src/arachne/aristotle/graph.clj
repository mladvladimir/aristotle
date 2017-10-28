(ns arachne.aristotle.graph
  "Tools for converting Clojure data to an Jena Graph representation"
  (:require [arachne.aristotle.registry :as reg])
  (:import [clojure.lang Keyword Symbol]
           [java.net URL URI]
           [java.util GregorianCalendar Calendar Date Map Collection List]
           [org.apache.jena.graph Node NodeFactory Triple GraphUtil]
           [org.apache.jena.datatypes.xsd XSDDatatype]
           [javax.xml.bind DatatypeConverter])
  (:refer-clojure :exclude [reify]))

(defprotocol AsTriples
  "An object that can be converted to a collection of Jena Triples."
  (triples [obj] "Convert this object to a collection of Jena Triples"))

(defprotocol AsNode
  "An object that can be interpreted as a node in an RDF graph."
  (node [obj] "Convert this object to a Jena RDFNode."))

(extend-protocol AsNode
  Keyword
  (node [kw] (NodeFactory/createURI (reg/iri kw)))
  URI
  (node [uri] (NodeFactory/createURI (.toString uri)))
  URL
  (node [url] (NodeFactory/createURI (.toString url)))
  Symbol
  (node [sym] (NodeFactory/createBlankNode (str sym)))
  String
  (node [obj]
    (if-let [uri (second (re-find #"^<(.*)>$" obj))]
      (NodeFactory/createURI uri)
      (NodeFactory/createLiteralByValue obj XSDDatatype/XSDstring)))
  Long
  (node [obj]
    (NodeFactory/createLiteralByValue obj XSDDatatype/XSDlong))
  Double
  (node [obj]
    (NodeFactory/createLiteralByValue obj XSDDatatype/XSDdouble))
  Boolean
  (node [obj]
    (NodeFactory/createLiteralByValue obj XSDDatatype/XSDboolean))
  java.math.BigDecimal
  (node [obj]
    (NodeFactory/createLiteralByValue obj XSDDatatype/XSDdecimal))
  Date
  (node [obj]
    (node (doto (GregorianCalendar.) (.setTime obj))))
  Calendar
  (node [obj]
    (NodeFactory/createLiteral
      (DatatypeConverter/printDateTime obj)
      XSDDatatype/XSDdateTime))
  Node
  (node [node] node))

(defn- triple?
  "Does an object look like a triple?"
  [obj]
  (and (instance? List obj)
       (= 3 (count obj))
       (not-any? coll? obj)))

(defn- triple
  "Build a Triple object"
  [s p o]
  (Triple/create (node s) (node p) (node o)))

(extend-protocol AsTriples

  Triple
  (triples [triple] [triple])

  Collection
  (triples [coll]
    (if (triple? coll)
      [(apply triple (map node coll))]
      (mapcat triples coll)))

  Map
  (triples [m]
    (let [subject (if-let [about (:rdf/about m)]
                    (node about)
                    (NodeFactory/createBlankNode))
          child-map-triples (fn [property child-map]
                              (let [child-triples (triples child-map)
                                    child-subject (.getSubject (first child-triples))]
                                (cons
                                  (triple subject property child-subject)
                                  child-triples)))
          result-triples (mapcat (fn [[k v]]
                                   (cond
                                     (instance? Map v)
                                     (child-map-triples k v)

                                     (instance? Collection v)
                                     (mapcat (fn [child]
                                               (if (instance? Map child)
                                                 (child-map-triples k v)
                                                 [(triple subject k child)])) v)

                                     :else
                                     [(triple subject k v)]))
                           (dissoc m :rdf/about))]
      (with-meta result-triples {::subject subject}))))

(defn reify
  "Given a collection of Triples, reify each triple and return a seq of
   triples explicitly stating the rdf type, subject, predicate and object of
   each input triple."
  [triples]
  (mapcat (fn [t]
            (let [n (NodeFactory/createBlankNode)]
              (arachne.aristotle.graph/triples
                {:rdf/about n
                 :rdf/type :rdf/Statement
                 :rdf/subject (.getSubject t)
                 :rdf/predicate (.getPredicate t)
                 :rdf/object (.getObject t)})))
    triples))


(comment

  (import [org.apache.jena.graph Factory GraphUtil])

  (reg/prefix :foaf "http://xmlns.com/foaf/0.1/")
  (reg/prefix :cfg "http://arachne-framework.org/config/")

  (def ts (triples [["<http://example.com/#luke>" :foaf/name "Luke"]
                    ["<http://example.com/#luke>" :foaf/age 32]]))

  (def rs (reify ts))

  (def ms (->> rs
            (map #(.getSubject %))
            (set)
            (map (fn [stmt]
                   (triple "<http://example.com/#my-tx" :cfg/tx-stmts stmt)))))

  (def g (Factory/createDefaultGraph))

  (GraphUtil/add g ts)
  (GraphUtil/add g rs)
  (GraphUtil/add g ms)

  (import [org.apache.jena.rdf.model ModelFactory])


  (def m (ModelFactory/createModelForGraph g))

  (iterator-seq (.listReifiedStatements m))

  (.write m *out* "TURTLE")

  )

