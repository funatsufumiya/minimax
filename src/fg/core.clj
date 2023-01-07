(ns fg.core
  (:require
    [bgfx.core :as bgfx]
    [minimax.object :as obj]
    [fg.dev]
    [fg.model :as md]
    [fg.clock :as clock]
    [minimax.objects.camera :as camera]
    [minimax.passes :as passes]
    [minimax.ui :as ui]
    [fg.state :as state]
    [fg.listeners :as listeners]
    [minimax.objects.light :as light]
    [minimax.objects.scene :as scene]
    [minimax.glfw :as glfw]
    [fg.passes.shadow :as pass.shadow]
    [fg.passes.geometry :as pass.geom]
    [fg.passes.combine :as pass.comb]
    [fg.passes.picking :as pass.picking]
    [minimax.debug :as debug]
    [fg.ui])
  (:import (java.util.function Consumer)
           (org.joml Vector3f)
           (org.lwjgl.bgfx BGFXInit BGFXResolution)
           (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWNativeCocoa GLFWNativeWin32 GLFWNativeX11)
           (org.lwjgl.system Configuration MemoryUtil Platform)))

(set! *warn-on-reflection* true)

(when (= (Platform/get) Platform/MACOSX)
  (.set Configuration/GLFW_LIBRARY_NAME "glfw_async"))

;; GLFW window

(when-not (GLFW/glfwInit)
  (throw (IllegalStateException. "Unable to initialize GLFW")))

(def error-callback
  (GLFWErrorCallback/createPrint System/err))

(GLFW/glfwSetErrorCallback error-callback)

(GLFW/glfwWindowHint GLFW/GLFW_CLIENT_API GLFW/GLFW_NO_API)
(GLFW/glfwWindowHint GLFW/GLFW_COCOA_RETINA_FRAMEBUFFER GLFW/GLFW_TRUE)

(def window
  (glfw/create-window
    {:width (:width @state/state)
     :height (:height @state/state)
     :title "Minimax"}))

(defn detect-dpr []
  (let [x (MemoryUtil/memAllocFloat 1)
        y (MemoryUtil/memAllocFloat 1)
        _ (GLFW/glfwGetMonitorContentScale
            (GLFW/glfwGetPrimaryMonitor) x y)
        dpr (.get x)
        _ (MemoryUtil/memFree x)
        _ (MemoryUtil/memFree y)]
    dpr))

(state/set-size (detect-dpr))

(let [^BGFXInit init (bgfx/create-init)]
  (.resolution init
    (reify Consumer
      (accept [this it]
        (-> ^BGFXResolution it
            (.width (:vwidth @state/state))
            (.height (:vheight @state/state))
            (.reset listeners/reset-flags)
            (.format listeners/texture-format)))))

  (condp = (Platform/get)
    Platform/MACOSX
    (-> (.platformData init)
        (.nwh (GLFWNativeCocoa/glfwGetCocoaWindow window)))

    Platform/LINUX
    (-> (.platformData init)
        (.ndt (GLFWNativeX11/glfwGetX11Display))
        (.nwh (GLFWNativeX11/glfwGetX11Window window)))

    Platform/WINDOWS
    (-> (.platformData init)
        (.nwh (GLFWNativeWin32/glfwGetWin32Window window))))

  (when-not (bgfx/init init)
    (throw (RuntimeException. "Error initializing bgfx renderer"))))

(println (str "bgfx renderer: " (bgfx/get-renderer-name (bgfx/get-renderer-type))))

(ui/init (:vwidth @state/state) (:vheight @state/state))

(def camera
  (atom
    (camera/create-perspective-camera
      {:fov 60
       :aspect (/ (:vwidth @state/state) (:vheight @state/state))
       :near 0.1
       :far 100})))

(def d-light
  (light/create-directional-light
    {:name "u_light_pos"
     :position (:light-pos @state/state)
     :color (Vector3f. 1.0 1.0 1.0)}))

(def model
  (md/load-model "models/castle.glb"))

(defn fps []
  (Math/round (float (/ 1e3 (/ (clock/dt) 1e6)))))

;; debug
(def selected-object (atom nil))
(def debug-box @debug/debug-box)

;; scene
(def scene
  (atom (scene/create
          {:name "MainScene"
           :children [d-light (:scene model) debug-box]})))

(def castle-obj
  (obj/find-by-name @scene "castle_root"))

(def cloud-1-obj
  (obj/find-by-name @scene "cloud_1"))

(def cloud-2-obj
  (obj/find-by-name @scene "cloud_2"))

(defn render []
  (let [dt (/ (clock/dt) 1e9)
        pos1 (obj/position cloud-1-obj)
        pos2 (obj/position cloud-2-obj)
        y (-> (Math/sin (/ (clock/time) 200))
              (/ 100))
        x (-> (Math/sin (/ (clock/time) 1000))
              (/ 100))
        z (-> (Math/cos (/ (clock/time) 1000))
              (/ 100))]

    (obj/rotate-y cloud-1-obj (* dt 0.3))
    (obj/set-position-y cloud-1-obj (+ (.y pos1) y))
    (obj/set-position-x cloud-1-obj (+ (.x pos1) x))
    (obj/set-position-z cloud-1-obj (+ (.z pos1) z))

    (obj/rotate-y cloud-2-obj (* dt -0.3))
    (obj/set-position-y cloud-2-obj (+ (.y pos2) y))
    (obj/set-position-x cloud-2-obj (+ (.x pos2) z))
    (obj/set-position-z cloud-2-obj (+ (.z pos2) x))

    (obj/rotate-y castle-obj (* dt 0.1))

    (when-let [obj @selected-object]
      (debug/set-object-transform obj debug-box))))

(defn render-ui []
  (let [dt (/ (clock/dt) 1e6)]
    (fg.ui/ui-root dt (:width @state/state) (:height @state/state) @scene selected-object)))

;; Rendering loop
(def curr-frame (atom nil))

(defn run []
  (clock/step)

  (pass.shadow/setup d-light) ;; setup pass shadow
  (pass.geom/setup camera) ;; render pass geometry
  (pass.picking/setup camera) ;; picking pass

  (render)

  (obj/render @scene (:id passes/shadow)) ;; fill shadow map texture
  (obj/render @scene (:id passes/geometry)) ;; fill screen space texture
  (obj/render @scene (:id passes/picking)) ;; picking id pass
  (ui/render (select-keys @state/state
               [:width :height :dpr :mx :my :sx :sy
                :mouse-button :mouse-button-action])
             render-ui) ;; ui pass
  (pass.comb/render) ;; render combine pass

  #_#_
  (pass.picking/pick @curr-frame)
  (pass.picking/blit)

  ;; next frame
  (reset! curr-frame (bgfx/frame)))

(listeners/set-listeners window camera run)

(defn -main [& args]
  (fg.dev/start)

  (while (not (GLFW/glfwWindowShouldClose window))
    (state/reset-state)
    (GLFW/glfwPollEvents)
    (run))

  ;; Disposing the program
  (ui/shutdown)
  (bgfx/shutdown)
  (glfw/destroy-window)
  (GLFW/glfwTerminate)
  (.free (GLFW/glfwSetErrorCallback nil))
  ;; Stop file watcher
  (fg.dev/stop))
