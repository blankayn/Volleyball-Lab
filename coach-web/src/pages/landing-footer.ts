import { icon } from "../lib/icons";

export function landingFooter(): string {
  return `<footer class="landing-footer">
    <div class="wrap footer-main">
      <div class="footer-identity">
        <a class="brand footer-brand" href="#/" aria-label="Volleyball Lab home"><img class="brand-mark" src="/assets/volleyball-lab-logo.png" alt="" /><span>Volleyball Lab<small>The coach portal</small></span></a>
        <p>Better practice.<br />A stronger next point.</p>
        <span class="footer-caption">Built around the work you put in.</span>
      </div>
      <nav class="footer-column" aria-label="Explore the landing page"><h2>Explore</h2><a href="#features">The toolkit</a><a href="#how-it-works">How it works</a><a href="#about">Our approach</a></nav>
      <nav class="footer-column" aria-label="Coach account"><h2>Coach portal</h2><a href="#/login">Sign in</a><a href="#/register">Create an account</a><a href="#/app">Your dashboard</a></nav>
      <div class="footer-invite"><span class="footer-caption">Your next session starts here</span><h2>Make every <br />rep count.</h2><a href="#/register">Join Volleyball Lab ${icon("arrow")}</a></div>
    </div>
    <div class="wrap footer-bottom"><span>© ${new Date().getFullYear()} Volleyball Lab</span><span>${icon("court")} Made for the game. Built for the team.</span><a href="#top">Back to top ${icon("arrow")}</a></div>
  </footer>`;
}
