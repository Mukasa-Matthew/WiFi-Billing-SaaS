"use client";

import { Suspense, useEffect, useLayoutEffect, useMemo, useRef, type RefObject } from "react";
import { Canvas, useThree } from "@react-three/fiber";
import { ContactShadows, Environment, useGLTF } from "@react-three/drei";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import * as THREE from "three";

gsap.registerPlugin(ScrollTrigger);

const MODEL_URL = "/models/airpairy-router.glb";

/** Fine plastic grain: a bump map, so highlights break up instead of looking like CG. */
function grainTexture() {
  const size = 256;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const context = canvas.getContext("2d")!;
  const image = context.createImageData(size, size);
  for (let i = 0; i < image.data.length; i += 4) {
    const v = 110 + Math.random() * 70;
    image.data[i] = image.data[i + 1] = image.data[i + 2] = v;
    image.data[i + 3] = 255;
  }
  context.putImageData(image, 0, 0);
  const texture = new THREE.CanvasTexture(canvas);
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
  texture.repeat.set(2.4, 2.4);
  return texture;
}

/** The printed board: copper traces under solder mask, vias, and white silkscreen. */
function boardTexture() {
  const width = 2048, height = 1344;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const c = canvas.getContext("2d")!;
  c.fillStyle = "#0A4F4C";
  c.fillRect(0, 0, width, height);

  c.lineCap = "round";
  c.lineJoin = "round";
  for (let i = 0; i < 340; i++) {
    let x = Math.random() * width, y = Math.random() * height;
    c.strokeStyle = Math.random() > 0.25 ? "rgba(26,132,122,0.75)" : "rgba(214,170,84,0.35)";
    c.lineWidth = Math.random() > 0.8 ? 7 : 2.5;
    c.beginPath();
    c.moveTo(x, y);
    for (let s = 0; s < 3; s++) {
      const run = 50 + Math.random() * 280;
      const dir = Math.floor(Math.random() * 4);
      if (dir === 0) x += run; else if (dir === 1) x -= run; else if (dir === 2) { x += run * 0.7; y += run * 0.7; } else y += run;
      c.lineTo(x, y);
    }
    c.stroke();
  }
  for (let i = 0; i < 260; i++) {
    const x = Math.random() * width, y = Math.random() * height;
    c.fillStyle = "#D6AA54";
    c.beginPath(); c.arc(x, y, 5.5, 0, Math.PI * 2); c.fill();
    c.fillStyle = "#06302E";
    c.beginPath(); c.arc(x, y, 2.4, 0, Math.PI * 2); c.fill();
  }

  c.fillStyle = "rgba(236,244,240,0.92)";
  c.strokeStyle = "rgba(236,244,240,0.85)";
  c.lineWidth = 2.5;
  c.font = "600 34px Arial, sans-serif";
  c.fillText("AIRPAIRY AP-1  REV B", 1120, 1250);
  c.font = "500 19px Arial, sans-serif";
  [["U1", 690, 560], ["U2", 1120, 330], ["U3", 1120, 790], ["C14", 150, 300], ["C15", 150, 470], ["L2", 410, 150], ["L3", 540, 150], ["J1", 300, 90], ["RF1", 1500, 290], ["RF2", 1500, 770], ["ANT1", 1860, 90], ["ANT2", 110, 90]]
    .forEach(([text, x, y]) => c.fillText(String(text), Number(x), Number(y)));
  c.strokeRect(420, 300, 560, 560);
  c.strokeRect(1400, 250, 460, 380);
  c.strokeRect(1400, 710, 460, 380);

  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.anisotropy = 8;
  return texture;
}

function surfaceMaterials() {
  const grain = grainTexture();
  const board = boardTexture();
  const shell = (color: string, roughness: number) => new THREE.MeshPhysicalMaterial({ color, roughness, metalness: 0.15, clearcoat: 0.28, clearcoatRoughness: 0.42, bumpMap: grain, bumpScale: 0.35 });
  const materials: Record<string, THREE.Material> = {
    "Lagoon Ink": shell("#0B3238", 0.44),
    Graphite: shell("#10191B", 0.52),
    "Solder Mask": new THREE.MeshPhysicalMaterial({ map: board, roughness: 0.42, metalness: 0.05, clearcoat: 0.55, clearcoatRoughness: 0.28 }),
    "Smoked Glass": new THREE.MeshPhysicalMaterial({ color: "#0E2E33", roughness: 0.05, metalness: 0.1, clearcoat: 1, clearcoatRoughness: 0.03, transparent: true, opacity: 0.62, envMapIntensity: 1.5, depthWrite: false }),
    Antenna: shell("#0B1416", 0.5),
  };
  return { materials, textures: [grain, board] };
}

function Router({ trackRef, reduced, onReady }: { trackRef: RefObject<HTMLElement | null>; reduced: boolean; onReady: () => void }) {
  const { scene } = useGLTF(MODEL_URL, false, true);
  const spin = useRef<THREE.Group>(null);
  const invalidate = useThree((state) => state.invalidate);
  const viewport = useThree((state) => state.viewport);
  const surfaces = useMemo(() => surfaceMaterials(), []);

  useLayoutEffect(() => {
    scene.traverse((node) => {
      if (!(node instanceof THREE.Mesh)) return;
      node.castShadow = true;
      node.receiveShadow = true;
      const upgraded = surfaces.materials[(node.material as THREE.Material).name];
      if (upgraded) {
        (node.material as THREE.Material).dispose();
        node.material = upgraded;
      }
    });
    invalidate();
  }, [scene, surfaces, invalidate]);

  useLayoutEffect(() => {
    const group = spin.current;
    if (!group || !trackRef.current) return;
    const context = gsap.context(() => {
      if (reduced) {
        // A still, half-open view: no motion tied to scrolling.
        scene.children.forEach((mesh) => { mesh.position.z = (mesh.userData.explode ?? 0) * 0.5; });
        invalidate();
        return;
      }
      const timeline = gsap.timeline({
        defaults: { ease: "none", duration: 1 },
        // A numeric scrub adds the inertia: the model catches up to the scrollbar.
        scrollTrigger: { trigger: trackRef.current, start: "top top", end: "bottom bottom", scrub: 1.2 },
        onUpdate: invalidate,
      });
      scene.children.forEach((mesh) => {
        const travel = mesh.userData.explode;
        if (travel !== undefined) timeline.to(mesh.position, { z: mesh.position.z + travel }, 0);
      });
      timeline.to(group.rotation, { y: 0.5, x: 0.22 }, 0);
    });
    return () => context.revert();
  }, [scene, reduced, trackRef, invalidate]);

  useEffect(() => { onReady(); }, [onReady]);

  /* The GLTF cache would keep these alive after the page is gone. The release
     waits a tick so a remount can cancel it: clearing the cache while still
     mounted suspends the scene, refetches the model and loops. */
  const release = useRef<number | undefined>(undefined);
  useEffect(() => {
    window.clearTimeout(release.current);
    return () => {
      release.current = window.setTimeout(() => {
        scene.traverse((node) => {
          if (!(node instanceof THREE.Mesh)) return;
          node.geometry.dispose();
          (Array.isArray(node.material) ? node.material : [node.material]).forEach((material) => material.dispose());
        });
        surfaces.textures.forEach((texture) => texture.dispose());
        useGLTF.clear(MODEL_URL);
      }, 0);
    };
  }, [scene, surfaces]);

  // Fit the fully exploded router into this canvas, whatever shape it is.
  const scale = Math.min(viewport.width / 9.4, viewport.height / 7.6);

  return (
    <group ref={spin} position={[0, -1.1 * scale, 0]} rotation={[0.46, -0.62, 0]} scale={scale}>
      <group rotation={[-Math.PI / 2, 0, 0]}>
        <primitive object={scene} />
      </group>
    </group>
  );
}

export default function RouterScene({ trackRef, reduced, onReady }: { trackRef: RefObject<HTMLElement | null>; reduced: boolean; onReady: () => void }) {
  return (
    <Canvas
      shadows={{ type: THREE.PCFShadowMap }}
      frameloop="demand"
      dpr={[1, 1.75]}
      camera={{ position: [0, 0.9, 11], fov: 30 }}
      gl={{ antialias: true, alpha: true, powerPreference: "high-performance", toneMappingExposure: 1.05 }}
      fallback={null}
      aria-hidden
    >
      <Suspense fallback={null}>
        <Environment files="/hdri/studio.hdr" />
        <directionalLight position={[-3.5, 7, 4]} intensity={1.15} castShadow shadow-mapSize={[2048, 2048]} shadow-bias={-0.0004} shadow-normalBias={0.02} shadow-radius={5}>
          <orthographicCamera attach="shadow-camera" args={[-6, 6, 6, -6, 1, 20]} />
        </directionalLight>
        <Router trackRef={trackRef} reduced={reduced} onReady={onReady} />
        <ContactShadows position={[0, -2.75, 0]} scale={18} blur={2.8} far={8} opacity={0.65} resolution={512} color="#02100F" />
      </Suspense>
    </Canvas>
  );
}
