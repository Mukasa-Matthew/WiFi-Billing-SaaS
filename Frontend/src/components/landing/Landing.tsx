"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useRef, useState, useSyncExternalStore } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { ArrowRight, Radio } from "@/components/icons";

gsap.registerPlugin(ScrollTrigger, useGSAP);

// three.js only ever loads on this page, and only in the browser.
const RouterScene = dynamic(() => import("@/components/landing/RouterScene"), { ssr: false });

const STORIES = [
  { title: "Link a router with one setup command.", body: "Paste it into your MikroTik and AirPairy finishes the hotspot, login and portal setup." },
  { title: "Customers pay however suits them.", body: "MTN, Airtel or a printed voucher. Access starts at first login, not at the moment of sale." },
  { title: "See every location, live.", body: "Routers report in every two minutes, so you know what's online and what each site earns." },
];

const FEATURES = [
  { title: "Packages and vouchers", body: "Set prices, speeds and time limits, then generate codes in batches of up to 1,000." },
  { title: "Mobile money and a wallet", body: "Collect payments in UGX, track your balance and withdraw to mobile money." },
  { title: "Your own login page", body: "Customers buy and connect through a branded portal hosted on your router." },
  { title: "Customers and sessions", body: "See who's online, how much data they use, and disconnect a session when you need to." },
];

const REDUCED = "(prefers-reduced-motion: reduce)";
function subscribeMotion(cb: () => void) {
  const query = window.matchMedia(REDUCED);
  query.addEventListener("change", cb);
  return () => query.removeEventListener("change", cb);
}

function subscribeScroll(cb: () => void) {
  window.addEventListener("scroll", cb, { passive: true });
  return () => window.removeEventListener("scroll", cb);
}

/** Scroll-driven landing for signed-out visitors: an exploded 3D router beside the product story. */
export default function Landing() {
  const track = useRef<HTMLElement>(null);
  const [ready, setReady] = useState(false);
  const onReady = useCallback(() => setReady(true), []);
  const reduced = useSyncExternalStore(subscribeMotion, () => window.matchMedia(REDUCED).matches, () => false);
  // Clear over the hero; a solid backdrop once content can pass underneath.
  const scrolled = useSyncExternalStore(subscribeScroll, () => window.scrollY > 8, () => false);

  useGSAP(() => {
    const timeline = gsap.timeline({
      defaults: { ease: "none" },
      scrollTrigger: { trigger: track.current, start: "top top", end: "bottom bottom", scrub: 0.6 },
    });
    timeline.to("[data-story='hero']", { autoAlpha: 0, y: reduced ? 0 : -28, duration: 0.14 }, 0.06);
    STORIES.forEach((_, i) => {
      const at = 0.24 + i * 0.26;
      timeline.fromTo(`[data-story='${i}']`, { autoAlpha: 0, y: reduced ? 0 : 28 }, { autoAlpha: 1, y: 0, duration: 0.08 }, at);
      if (i < STORIES.length - 1) timeline.to(`[data-story='${i}']`, { autoAlpha: 0, y: reduced ? 0 : -28, duration: 0.08 }, at + 0.16);
    });
  }, { scope: track, dependencies: [reduced], revertOnUpdate: true });

  return (
    <div className="bg-[#0B1F36] text-white">
      <header className={`fixed inset-x-0 top-0 z-30 flex h-[4.5rem] items-center justify-between border-b px-5 transition-[background-color,border-color] duration-300 sm:px-10 ${scrolled ? "border-white/10 bg-[#0B1F36]/95 backdrop-blur-md" : "border-transparent bg-transparent"}`}>
        <Link href="/" aria-label="AirPairy home" className="flex min-h-11 items-center gap-2.5">
          <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-[#1D4ED8]">
            <Radio className="h-5 w-5 text-white" aria-hidden />
          </span>
          <span className="text-xl font-bold tracking-tight">AirPairy</span>
        </Link>
        <Link href="/login" className="inline-flex min-h-11 items-center gap-1.5 rounded-full bg-white px-5 text-sm font-semibold text-[#122B49] transition-transform duration-200 hover:scale-[1.03] active:scale-[0.98]">
          Sign in
        </Link>
      </header>

      <main>
        <section ref={track} aria-label="How AirPairy works" className="relative h-[300vh]">
          <div className="sticky top-0 h-screen overflow-hidden">
            <div aria-hidden className="absolute inset-0 bg-[radial-gradient(70rem_50rem_at_75%_60%,rgb(29_78_216/0.42),transparent_70%)]" />
            {/* Text and router get their own areas, so they can never overlap: stacked on tall screens, side by side on wide ones. */}
            <div className="landing-stage">
              {!ready && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-3" role="status">
                  <div className="h-8 w-8 animate-spin rounded-full border-2 border-[#8AB4FF] border-t-transparent" />
                  <p className="text-sm text-[#C3D6F0]">Powering up the router…</p>
                </div>
              )}
              <div className={`absolute inset-0 transition-opacity duration-700 ${ready ? "opacity-100" : "opacity-0"}`}>
                <RouterScene trackRef={track} reduced={reduced} onReady={onReady} />
              </div>
            </div>

            <div className="landing-copy pointer-events-none relative mx-auto flex h-full max-w-7xl px-5 sm:px-10">
              <div className="landing-text relative w-full max-w-xl">
                <div data-story="hero">
                  <h1 className="landing-title font-bold leading-[0.98] tracking-[-0.04em]">Turn a MikroTik router into a paying WiFi business.</h1>
                  <p className="landing-body mt-4 leading-relaxed text-[#C3D6F0]">AirPairy runs your packages, vouchers, mobile money and the page your customers log in through.</p>
                </div>
                {STORIES.map((story, i) => (
                  <div key={story.title} data-story={i} className="invisible absolute inset-x-0 top-0">
                    <h2 className="landing-title font-bold leading-[1.02] tracking-[-0.035em]">{story.title}</h2>
                    <p className="landing-body mt-4 leading-relaxed text-[#C3D6F0]">{story.body}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        <section className="relative bg-[#0B1F36] px-5 py-24 sm:px-10 sm:py-32">
          <div className="mx-auto grid max-w-7xl gap-12 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] lg:gap-20">
            <div className="lg:sticky lg:top-32 lg:self-start">
              <h2 className="text-[2.2rem] font-bold leading-[1.02] tracking-[-0.035em] sm:text-[3.2rem]">Everything a hotspot business runs on.</h2>
              <Link href="/login" className="mt-8 inline-flex min-h-12 items-center gap-2 rounded-full bg-[#1D4ED8] px-6 text-sm font-semibold text-white transition-[transform,background-color] duration-200 hover:scale-[1.03] hover:bg-[#2563EB] active:scale-[0.98]">
                Sign in to your account <ArrowRight className="h-4 w-4" aria-hidden />
              </Link>
            </div>
            <dl>
              {FEATURES.map((feature) => (
                <div key={feature.title} className="border-t border-white/15 py-7 last:border-b">
                  <dt className="text-xl font-semibold tracking-[-0.015em] sm:text-2xl">{feature.title}</dt>
                  <dd className="mt-2 max-w-lg text-[15px] leading-relaxed text-[#C3D6F0]">{feature.body}</dd>
                </div>
              ))}
            </dl>
          </div>
        </section>
      </main>

      <footer className="border-t border-white/10 px-5 py-8 text-xs text-[#9DB6D9] sm:px-10">
        <p className="mx-auto max-w-7xl">AirPairy is a product by Thewton.</p>
      </footer>
    </div>
  );
}
