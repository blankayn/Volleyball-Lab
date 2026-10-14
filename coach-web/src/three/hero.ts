/* Landing hero scene: a volleyball over a fading court grid.
   Procedural throughout — no model files. Slow idle motion with pointer
   parallax; renders one static frame when reduced motion is requested. */

import * as THREE from "three";

function ballTexture(): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = 1024;
  c.height = 512;
  const g = c.getContext("2d");
  if (!g) throw new Error("2d context unavailable");
  g.fillStyle = "#f4f1ea";
  g.fillRect(0, 0, c.width, c.height);
  // Panel seams: a few bold curved strokes in ink and rust.
  g.lineCap = "round";
  const seam = (color: string, width: number, pts: [number, number][]) => {
    g.strokeStyle = color;
    g.lineWidth = width;
    g.beginPath();
    g.moveTo(pts[0]?.[0] ?? 0, pts[0]?.[1] ?? 0);
    for (let i = 1; i < pts.length - 1; i += 2) {
      const p = pts[i];
      const q = pts[i + 1];
      if (p && q) g.quadraticCurveTo(p[0], p[1], q[0], q[1]);
    }
    g.stroke();
  };
  seam("#141c33", 10, [[60, 60], [300, 140], [520, 90], [760, 180], [990, 140]]);
  seam("#cc5500", 10, [[40, 400], [280, 320], [520, 400], [760, 310], [1000, 390]]);
  seam("#141c33", 8, [[180, 0], [220, 180], [180, 360], [220, 512]]);
  seam("#141c33", 8, [[700, 0], [660, 180], [700, 360], [660, 512]]);
  // Subtle panel shading.
  const shade = g.createLinearGradient(0, 0, 0, 512);
  shade.addColorStop(0, "rgba(20,28,51,0.10)");
  shade.addColorStop(0.5, "rgba(20,28,51,0)");
  shade.addColorStop(1, "rgba(20,28,51,0.14)");
  g.fillStyle = shade;
  g.fillRect(0, 0, c.width, c.height);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

export interface HeroHandle {
  dispose(): void;
}

export function mountHero(container: HTMLElement): HeroHandle {
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 100);
  camera.position.set(0, 1.1, 7.2);
  camera.lookAt(0, 0.4, 0);

  scene.add(new THREE.AmbientLight(0xf0f8ff, 0.9));
  const key = new THREE.DirectionalLight(0xffffff, 2.2);
  key.position.set(4, 6, 5);
  scene.add(key);
  const rim = new THREE.DirectionalLight(0xcc5500, 1.1);
  rim.position.set(-5, 2, -3);
  scene.add(rim);

  // Ball.
  const ball = new THREE.Mesh(
    new THREE.SphereGeometry(1.55, 64, 64),
    new THREE.MeshStandardMaterial({ map: ballTexture(), roughness: 0.55, metalness: 0.05 }),
  );
  ball.position.set(0, 0.85, 0);
  scene.add(ball);

  // Court grid fading into the dark.
  const grid = new THREE.GridHelper(26, 26, 0x8fa0c8, 0x2c3a5e);
  grid.position.y = -1.35;
  const gridMat = grid.material as THREE.Material;
  gridMat.transparent = true;
  gridMat.opacity = 0.5;
  scene.add(grid);

  // Net hint: two posts and a translucent tape line behind the ball.
  const postMat = new THREE.MeshStandardMaterial({ color: 0xdce2ff, roughness: 0.4 });
  const postGeo = new THREE.CylinderGeometry(0.035, 0.035, 3.4, 12);
  for (const x of [-4.4, 4.4]) {
    const post = new THREE.Mesh(postGeo, postMat);
    post.position.set(x, 0.3, -3.2);
    scene.add(post);
  }
  const tape = new THREE.Mesh(
    new THREE.BoxGeometry(8.8, 0.09, 0.02),
    new THREE.MeshStandardMaterial({ color: 0xf0f8ff, roughness: 0.6 }),
  );
  tape.position.set(0, 1.85, -3.2);
  scene.add(tape);

  // Dust particles drifting upward.
  const COUNT = 130;
  const positions = new Float32Array(COUNT * 3);
  for (let i = 0; i < COUNT; i++) {
    positions[i * 3] = (Math.random() - 0.5) * 12;
    positions[i * 3 + 1] = Math.random() * 5 - 1.5;
    positions[i * 3 + 2] = (Math.random() - 0.5) * 6 - 1;
  }
  const dustGeo = new THREE.BufferGeometry();
  dustGeo.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  const dust = new THREE.Points(
    dustGeo,
    new THREE.PointsMaterial({ color: 0xffd9bf, size: 0.035, transparent: true, opacity: 0.7 }),
  );
  scene.add(dust);

  const resize = () => {
    const w = container.clientWidth || 1;
    const h = container.clientHeight || 1;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  };
  resize();
  const onResize = () => resize();
  window.addEventListener("resize", onResize);

  let px = 0;
  let py = 0;
  const onPointer = (e: PointerEvent) => {
    const r = container.getBoundingClientRect();
    px = ((e.clientX - r.left) / r.width - 0.5) * 2;
    py = ((e.clientY - r.top) / r.height - 0.5) * 2;
  };
  window.addEventListener("pointermove", onPointer);

  let raf = 0;
  let alive = true;
  const clock = new THREE.Clock();

  const frame = () => {
    if (!alive) return;
    const t = clock.getElapsedTime();
    if (!reduceMotion) {
      ball.rotation.y = t * 0.22;
      ball.rotation.x = Math.sin(t * 0.3) * 0.12;
      ball.position.y = 0.85 + Math.sin(t * 0.8) * 0.12;
      const pos = dustGeo.attributes["position"] as THREE.BufferAttribute;
      for (let i = 0; i < COUNT; i++) {
        let y = pos.getY(i) + 0.0035;
        if (y > 3.6) y = -1.5;
        pos.setY(i, y);
      }
      pos.needsUpdate = true;
    }
    camera.position.x += (px * 0.7 - camera.position.x) * 0.04;
    camera.position.y += (1.1 - py * 0.4 - camera.position.y) * 0.04;
    camera.lookAt(0, 0.4, 0);
    renderer.render(scene, camera);
    if (!reduceMotion) raf = requestAnimationFrame(frame);
  };
  frame();

  return {
    dispose() {
      alive = false;
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", onResize);
      window.removeEventListener("pointermove", onPointer);
      scene.traverse((o) => {
        const mesh = o as THREE.Mesh;
        if (mesh.geometry) mesh.geometry.dispose();
        const mat = mesh.material as THREE.Material | THREE.Material[] | undefined;
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose());
        else mat?.dispose();
      });
      renderer.dispose();
      renderer.domElement.remove();
    },
  };
}
