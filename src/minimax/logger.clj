(ns minimax.logger)

(defn debug [& args]
  (apply println "[MINIMAX]" args))
