document.documentElement.classList.add('js');

const targets = document.querySelectorAll('.section .block, .section .lead, .pipeline > li, .layers > li, .models > article, .hardships > article, .roadmap > li, .tables, .controls, .impl > article');
targets.forEach(el => el.classList.add('reveal'));

const io = new IntersectionObserver(entries => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      entry.target.classList.add('in');
      io.unobserve(entry.target);
    }
  });
}, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });

targets.forEach(el => io.observe(el));
