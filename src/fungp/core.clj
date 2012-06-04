;;; Genetic programming in clojure
;;; ==============================
;;;
;;; Mike Vollmer, 2012, GPL
;;;
;;; What is this?
;;; -------------
;;;
;;; **fungp** is a parallel genetic programming library implemented in the
;;; Clojure programming language. The "fun" comes from functional, and because
;;; genetic programming can be fun! Also I'm bad at naming things.
;;;
;;; > There are only two hard things in Computer Science: cache invalidation,
;;; > naming things, and off-by-one errors.
;;; >
;;; > --- *Paraphrased from Phil Karlton*
;;; 
;;; The library is in its early stages right now, but it's usable. Currently it
;;; has the following features:
;;;
;;;  * Custom evaluation and reporting logic via function parameters
;;;  * Training data can be 
;;;  * Parallelism: subpopulations run in native threads
;;;  * Evolve and test functions of multiple arities
;;;
;;; How do I use it?
;;; ----------------
;;;
;;; Call the **run-gp** function. See the source code for the full list of 
;;; keyword parameters.
;;;
;;; Here's an example:
;;;
;;;     (run-gp {:gens iter :cycles cycle
;;;             :pop-size 6 :forest-size 50
;;;             :symbols symbols :funcs funcs
;;;             :range-max 1 :range-min -1
;;;             :max-depth 4 :min-depth 2
;;;             :repfunc repfunc  :reprate 1
;;;             :mutation-rate 0.1 :tournament-size 5
;;;             :actual actual :tests testdata})
;;;
;;; Functions are defined as a sequence of maps, each having keys :op,
;;; :arity, and :name. :op is for the function, :arity is the number
;;; of arguments, and :name is the symbol used to print it out if it's
;;; in the answer at the end (you probably want it to be the same as the
;;; name of the function).
;;;
;;; Here's an example:
;;;
;;;      [{:op *   :arity 2 :name '*}
;;;       {:op +   :arity 2 :name '+}
;;;       {:op sin :arity 1 :name 'sin}]
;;;
;;; For more information on how to use it, see the source code below.


(ns fungp.core
  (:use fungp.util))

(defn build-options
  "Take passed-in parameters and merge them with default parameters to construct
   the options hash that gets passed to the other functions."
  [o] (let [defaults {:term-max 1 :term-min -1 :depth-max 4 :depth-min 2
                      :mutation-rate 0.05 :tournament-size 5}]
        (merge defaults o)))

;;; Many of the functions below use the options hash built by the build-options
;;; function. In most cases this is invisible, since run-gp constructs the
;;; options hash and runs the parallel-generations function, but for purposes
;;; of testing or modification/extension you can do this manually with build-options.

(defn terminal
  "Return a random terminal for the source tree. Takes the options hash as parameter."
  [o] (if (flip 0.5) (rand-nth (:symbols o))
          (+ (:term-min o) (rand-int (- (:term-max o) (:term-min o))))))

;;; My method of random tree generation is a combination of the "grow" and "fill"
;;; methods of tree building, similar to Koza's "ramped half-and-half" method.

(defn build-tree
  "Build a random tree of lisp code. The tree will be filled up to the minimum depth,
   then grown to the maximum depth. Minimum and maximum depth are specified in the
   options, but can optionally be passed explicitly."
  ([o] (build-tree o (:depth-max o) (:depth-min o)))
  ([o depth-max depth-min]
     (if (or (zero? depth-max)
             (and (<= depth-min 0) (flip 0.5)))
       (terminal o) ;; insert a random terminal
       (let [f (rand-nth (:funcs o))]
         (cons (:op f) ;; cons the operation onto a sublist matching the arity of f
               (repeatedly (:arity f) #(build-tree o (- depth-max 1) (- depth-min 1))))))))

(defn max-tree-height
  "Find the maximum height of a tree."
  [tree] (if (not (seq? tree)) 0 (+ 1 (reduce max (map max-tree-height tree)))))

(defn rand-subtree
  "Return a random subtree of a list (presumably of lisp code)."
  ([tree] (rand-subtree tree (rand-int (max-tree-height tree))))
  ([tree n] (if (or (not (seq? tree)) (= n 0)) tree
                (recur (rand-nth (rest tree)) (rand-int (- n 1))))))

(defn replace-subtree
  "Replace a random subtree with a given subtree."
  ([tree sub] (replace-subtree tree sub (max-tree-height tree)))
  ([tree sub n] (if (or (not (seq? tree)) (= n 0)) sub
                    (let [r (+ 1 (rand-int (- (count (rest tree)) 1)))]                 
                      (concat (take r tree)
                              (list (replace-subtree
                                     (nth tree r) sub (rand-int (- n 1))))
                              (nthrest tree (+ r 1)))))))

;;; With rand-subtree and replace-subtree out of the way, the rest of the
;;; single-generation pass is pretty simple. Mutation and crossover both
;;; can easily be written in terms of rand-subtree and replace-subtree.

(defn mutate
  "Mutate a tree by substituting in a randomly-built tree of code."
  [o tree] (if (flip (:mutation-rate o))
             (replace-subtree tree (build-tree o)) tree))

(defn crossover
  "The crossover function is simple to define in terms of replace-subtree
   and rand-subtree. Basically, crossing over two trees involves selecting a
   random subtree from one tree, and placing it randomly in the other tree."
  [tree1 tree2] (replace-subtree tree1 (rand-subtree tree2)))

(defn build-forest
  "Returns a sequence of trees. A bunch of trees is a forest, right? Get it?"
  [o] (repeatedly (:forest-size o) #(build-tree o)))

;;; The selection process is convenient to express in lisp using heigher-order
;;; functions and **map**.

(defn find-error
  "Compares the output of the individual tree with the test data to calculate error."
  [o tree]
  (let [func (eval (list 'fn (:symbols o) tree))]
    (reduce + (map off-by (map (fn [arg] (apply func arg)) (:tests o)) (:actual o)))))

(defn forest-error
  "Runs find-error on every tree in parallel, and returns a map with keys
   :tree and :fitness in place of each tree. It needs the options hash because
   find-error needs to extract symbols, tests and actual."
  [o forest]
  (pmap (fn tree-error [tree] {:tree tree :fitness (find-error o tree)}) forest))

;;; A few of the following functions refer to **ferror**, a sequence returned
;;; by forest-error. It's a sequence of maps, each with keys for :tree and
;;; :fitness. The keys correspond to the source tree and the fitness score,
;;; respectively. It's simple enough to sort the sequence by fitness, for
;;; example.

(defn tournament-select-error
  "Select out a few individuals (tournament size is in o) and run a
   tournament amongst them. The two most fit in the tournament are crossed over
   to produce a child. Larger tournaments lead to more selective pressure."
  [o ferror]
  (let [selected (sort-by :fitness (repeatedly (:tournament-size o) #(rand-nth ferror)))]
    (crossover (:tree (first selected)) (:tree (second selected)))))

(defn tournament-select
  "Run tournament-select-error enough times to re-populate the forest. The options
   hash is passed so tournament-select-error can extract tournament-size."
  [o ferror]
  (repeatedly (count ferror) #(tournament-select-error o ferror)))

(defn get-best
  "Returns the best tree, given ferror."
  [ferror]
  (first (sort-by :fitness ferror)))

(defn generations
  "Run n generations of a forest. Over the course of one generation, the trees in
   the forest will go through selection, crossover, and mutation. The best individual
   seen so far in the forest is saved and passed on as the last parameter (it is nil
   when no best individual has been found)."
  [o n forest best]
  (if (or (zero? n)
          (and (not (nil? best))
               (zero? (:fitness best)))) ;; stop early when fitness is zero
    {:forest forest :best best} ;; return forest and current best
    (do (when (mod n (:reprate o))
          ((:repfunc o) best false))
        (let [ferror (forest-error o forest)
              cur-best (get-best ferror)
              new-best (if (nil? best) cur-best
                           (if (> (:fitness cur-best) (:fitness best)) best cur-best))
              new-forest (map (fn [tree] (mutate o tree))
                              (tournament-select o ferror))]
          ;; the recursive call for the next generation
          (recur o
                 (- n 1)
                 (if (nil? best) new-forest
                     (conj (rest new-forest)
                           (:tree new-best)))
                 new-best)))))

(defn build-population
  "Call build-forest repeatedly to fill a population."
  [o] (repeatedly (:pop-size o) #(build-forest o)))

(defn population-crossover
  "Individual trees migrate between forests."
  [population]
  (let [cross (map rand-nth population)]
    (map (fn [[forest selected]] (conj (rest (shuffle forest)) selected))
         (zipmap population cross))))

;;; **parallel-generations** is the function that runs the show. It runs the
;;; generations function defined above on each of the forests (and does so in
;;; parallel, thanks to Clojure's convenient parallelism features).
  
(defn parallel-generations
  "Spawn threads to run each of the forests for a specified amount of time, and
   cross over between the forests at specified intervals. If the search is starting
   from the beginning, the only necessary parameter is the options hash. The initial
   values of the other parameters can be inferred from it. If you're resuming a search
   you can simply pass in the population explicitly and this function will start
   where it left off."
  ([o] (parallel-generations o (:cycles o) (:gens o) nil nil))
  ([o cycles gens population best]
     (if (nil? population) (recur o cycles gens (build-population o) nil)
         (if (or (= cycles 0)
                 (and (not (nil? best))
                      (zero? (:fitness best))))
           {:population population :best best}
           (do (when (and (not (nil? best)) (mod cycles (:reprate o)))
                 ((:repfunc o) best true))
               (let [p (pmap (fn [forest] (generations o gens forest best)) population)
                     cur-pop (population-crossover (map :forest p))
                     all-bests (map :best p)
                     cur-best (first (sort-by :fitness all-bests))
                     new-best (if (nil? best) cur-best
                                  (if (> (:fitness cur-best) (:fitness best))
                                    best cur-best))]
                 (recur o (- cycles 1) gens cur-pop new-best)))))))


(defn run-gp
  "Create a population of source trees and evolve them to fit the test function
   and data passed in. This is probably the function you'll want to call."
  [o] (parallel-generations (build-options o)))
