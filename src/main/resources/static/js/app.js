/*
 * Dashboard behaviour, no framework: theme choice, toasts, confirm dialogs (for htmx's hx-confirm
 * and for plain forms with data-confirm), keyboard shortcuts, a loading bar for actions, and small
 * helpers (date picker navigation, password reveal). Every page works without it.
 */
(() => {
    const root = document.documentElement;

    /* Theme: "auto" follows the operating system; the choice is kept per browser. */
    function markTheme() {
        const current = root.dataset.theme || "auto";
        document.querySelectorAll("[data-theme-choice]").forEach(button =>
            button.setAttribute("aria-pressed", String(button.dataset.themeChoice === current)));
    }

    function chooseTheme(choice) {
        if (choice === "light" || choice === "dark") {
            root.dataset.theme = choice;
        } else {
            delete root.dataset.theme;
        }
        try {
            if (choice === "light" || choice === "dark") {
                localStorage.setItem("theme", choice);
            } else {
                localStorage.removeItem("theme");
            }
        } catch (ignored) {
            // Private mode: the choice lasts for this page only.
        }
        markTheme();
    }

    /* Sidebar: collapsed to a rail of icons or not (computers only); the choice is kept per browser. */
    function markSidebar() {
        const collapsed = root.dataset.sidebar === "collapsed";
        document.querySelectorAll("[data-sidebar-toggle]").forEach(button => {
            const label = collapsed ? button.dataset.labelExpand : button.dataset.labelCollapse;
            button.setAttribute("aria-expanded", String(!collapsed));
            button.setAttribute("aria-label", label);
            button.title = label + " ([)";
        });
    }

    document.addEventListener("click", event => {
        if (!event.target.closest("[data-sidebar-toggle]")) {
            return;
        }
        const collapse = root.dataset.sidebar !== "collapsed";
        if (collapse) {
            root.dataset.sidebar = "collapsed";
        } else {
            delete root.dataset.sidebar;
        }
        try {
            if (collapse) {
                localStorage.setItem("sidebar", "collapsed");
            } else {
                localStorage.removeItem("sidebar");
            }
        } catch (ignored) {
            // Private mode: the choice lasts for this page only.
        }
        markSidebar();
    });

    /* Toasts: success ones leave by themselves, errors stay until closed. */
    function initToasts() {
        document.querySelectorAll(".toast:not([data-ready])").forEach(toast => {
            toast.dataset.ready = "1";
            const close = () => {
                toast.classList.add("leaving");
                setTimeout(() => toast.remove(), 260);
            };
            toast.querySelector(".toast-close")?.addEventListener("click", close);
            if (!toast.classList.contains("error")) {
                setTimeout(close, 5000);
            }
        });
    }

    /*
     * Confirm dialog instead of the browser's confirm(); falls back to it without <dialog>. Red by
     * default; {calm: true} for a change that loses nothing, such as moving an appointment.
     */
    function ask(question, {calm = false} = {}) {
        const dialog = document.getElementById("confirm-dialog");
        if (!dialog || typeof dialog.showModal !== "function") {
            return Promise.resolve(window.confirm(question));
        }
        dialog.querySelector("[data-question]").textContent = question;
        dialog.classList.toggle("calm", calm);
        dialog.querySelector(".modal-icon use")?.setAttribute("href", calm ? "#i-move" : "#i-alert");
        const yes = dialog.querySelector("button[value=yes]");
        yes.classList.toggle("danger-solid", !calm);
        yes.classList.toggle("primary", calm);
        dialog.returnValue = "";
        dialog.showModal();
        return new Promise(resolve =>
            dialog.addEventListener("close", () => resolve(dialog.returnValue === "yes"), {once: true}));
    }

    document.addEventListener("htmx:confirm", event => {
        if (!event.detail.question) {
            return;
        }
        event.preventDefault();
        ask(event.detail.question).then(ok => {
            if (ok) {
                event.detail.issueRequest(true);
            }
        });
    });

    /* A form with data-confirm="question?" asks first; data-confirm-calm for one that loses nothing. */
    document.addEventListener("submit", event => {
        const form = event.target;
        if (!form.dataset.confirm || form.dataset.confirmed) {
            return;
        }
        event.preventDefault();
        ask(form.dataset.confirm, {calm: "confirmCalm" in form.dataset}).then(ok => {
            if (ok) {
                form.dataset.confirmed = "1";
                form.requestSubmit(event.submitter);
            }
        });
    });

    /* Shortcuts: an element with data-key="n" (or "slash", "ArrowLeft"...) is clicked or focused. */
    document.addEventListener("keydown", event => {
        if (event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented) {
            return;
        }
        if (event.target.closest("input, textarea, select, [contenteditable]") || document.querySelector("dialog[open]")) {
            return;
        }
        const key = event.key === "/" ? "slash" : event.key.length === 1 ? event.key.toLowerCase() : event.key;
        const target = document.querySelector(`[data-key="${key}"]`);
        if (!target) {
            return;
        }
        event.preventDefault();
        if (target.matches("input, textarea, select")) {
            target.focus();
        } else {
            target.click();
        }
    });

    /* A thin bar while an action runs; the 30-second refresh stays quiet. */
    document.addEventListener("htmx:beforeRequest", event => {
        if (event.detail.elt.closest("form, button")) {
            root.classList.add("is-loading");
        }
    });
    document.addEventListener("htmx:afterRequest", () => root.classList.remove("is-loading"));

    document.addEventListener("click", event => {
        const themeButton = event.target.closest("[data-theme-choice]");
        if (themeButton) {
            chooseTheme(themeButton.dataset.themeChoice);
        }
        const picker = event.target.closest(".date-input input");
        if (picker && typeof picker.showPicker === "function") {
            try {
                picker.showPicker();
            } catch (ignored) {
                // Some browsers only allow it from certain gestures; the field still works.
            }
        }
        const reveal = event.target.closest("[data-reveal]");
        if (reveal) {
            const input = document.getElementById(reveal.dataset.reveal);
            input.type = input.type === "password" ? "text" : "password";
            reveal.setAttribute("aria-pressed", String(input.type === "text"));
        }
        // Menus close when you click elsewhere.
        document.querySelectorAll("details.menu[open]").forEach(menu => {
            if (!menu.contains(event.target)) {
                menu.open = false;
            }
        });
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape") {
            document.querySelectorAll("details.menu[open]").forEach(menu => (menu.open = false));
        }
    });

    /*
     * Slide-over: a link with data-drawer opens its page's .drawer-content in #drawer instead of
     * navigating. Forms inside post normally, so the server's redirects and error pages still apply.
     */
    document.addEventListener("click", event => {
        const link = event.target.closest("a[data-drawer]");
        const drawer = document.getElementById("drawer");
        if (!link || !drawer || typeof drawer.showModal !== "function" || !window.htmx
            || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) {
            return;
        }
        if (link.dataset.justDragged) {
            event.preventDefault(); // the end of a drag, not a tap
            return;
        }
        event.preventDefault();
        const body = document.getElementById("drawer-body");
        body.innerHTML = '<div class="drawer-loading"><span class="spinner"></span></div>';
        if (!drawer.open) {
            drawer.showModal();
        }
        window.htmx.ajax("GET", link.href, {target: "#drawer-body", select: ".drawer-content", swap: "innerHTML ignoreTitle:true"})
            .then(() => body.querySelector("input:not([type=hidden]), select")?.focus());
    }, true);

    document.addEventListener("click", event => {
        const close = event.target.closest("[data-drawer-close]");
        const drawer = document.getElementById("drawer");
        if (close && drawer && drawer.open && drawer.contains(close)) {
            event.preventDefault();
            drawer.close();
        }
    });

    document.addEventListener("close", event => {
        if (event.target.id === "drawer") {
            document.getElementById("drawer-body").innerHTML = "";
        }
    }, true);

    /*
     * Week: move an appointment ([data-move-id]) by dragging it onto a free time ([data-drop-time]);
     * after a confirmation, #move-form posts the move. Pointer events, so mouse and touch work alike:
     * with a mouse, press and move; on a touch screen, press and hold for a moment first, so a quick
     * swipe still scrolls the page. Near the top or bottom edge the page scrolls while dragging; on a
     * phone, holding it over a day in the day strip jumps to that day.
     */
    const HOLD_MS = 350;
    const TOUCH_SLOP = 8;
    const MOUSE_SLOP = 5;
    const EDGE = 70;
    const STRIP_DWELL_MS = 300;
    let pending = null;
    let drag = null;

    function startDrag(start) {
        const block = start.block;
        block.classList.remove("holding");
        const rect = block.getBoundingClientRect();
        const ghost = block.cloneNode(true);
        ghost.removeAttribute("href");
        ghost.classList.add("drag-ghost");
        ghost.style.width = rect.width + "px";
        const label = document.createElement("span");
        label.className = "drag-to";
        ghost.appendChild(label);
        document.body.appendChild(ghost);
        // A finger would hide the copy: on touch it floats just above the finger instead of under it.
        drag = {block, ghost, label, target: null, chip: null, chipTimer: 0, pointerId: start.pointerId,
            x: start.x, y: start.y,
            offsetX: start.touch ? rect.width / 2 : start.x - rect.left,
            offsetY: start.touch ? rect.height + 34 : start.y - rect.top, frame: 0};
        block.classList.add("dragging");
        document.body.classList.add("is-dragging");
        navigator.vibrate?.(15);
        follow();
        drag.frame = requestAnimationFrame(autoScroll);
    }

    /* Places the ghost at the finger or mouse, lights up the free time below it and names it on the ghost. */
    function follow() {
        drag.ghost.style.transform = `translate(${drag.x - drag.offsetX}px, ${drag.y - drag.offsetY}px) rotate(-1.5deg)`;
        const point = document.elementFromPoint(drag.x, drag.y);
        const under = point?.closest("[data-drop-time]") ?? null;
        if (under !== drag.target) {
            drag.target?.classList.remove("drop-target");
            under?.classList.add("drop-target");
            drag.target = under;
            drag.label.textContent = under?.dataset.dropLabel ?? "";
        }
        const chip = point?.closest("[data-jump-day]") ?? null;
        if (chip !== drag.chip) {
            drag.chip?.classList.remove("drop-hover");
            clearTimeout(drag.chipTimer);
            chip?.classList.add("drop-hover");
            drag.chip = chip;
            if (chip) {
                // Instantly: a day sliding by under the finger mid-drag is harder to follow.
                drag.chipTimer = setTimeout(() => jumpToDay(chip.dataset.day, "auto"), STRIP_DWELL_MS);
            }
        }
    }

    /*
     * The band the days scroll through: below the day strip once it sticks to the top, above the
     * phone's bottom bar. Dragging near either end of it scrolls, faster the closer to the end.
     */
    function scrollBand() {
        const strip = document.querySelector(".day-strip");
        const stripBox = strip?.offsetParent ? strip.getBoundingClientRect() : null;
        const bar = document.querySelector(".sidebar");
        return {
            top: stripBox && stripBox.top <= 1 ? stripBox.bottom : 0,
            bottom: bar && getComputedStyle(bar).position === "fixed" ? bar.getBoundingClientRect().top : window.innerHeight
        };
    }

    function autoScroll() {
        if (!drag) {
            return;
        }
        const band = scrollBand();
        let step = 0;
        if (drag.y >= band.top && drag.y < band.top + EDGE) {
            step = -(4 + 14 * (band.top + EDGE - drag.y) / EDGE);
        } else if (drag.y > band.bottom - EDGE) {
            step = 4 + 14 * Math.min(1, (drag.y - band.bottom + EDGE) / EDGE);
        }
        if (step) {
            window.scrollBy(0, step);
            follow();
        }
        drag.frame = requestAnimationFrame(autoScroll);
    }

    function endDrag(drop) {
        const {block, ghost, target, chip, chipTimer, frame} = drag;
        cancelAnimationFrame(frame);
        clearTimeout(chipTimer);
        ghost.remove();
        block.classList.remove("dragging");
        target?.classList.remove("drop-target");
        chip?.classList.remove("drop-hover");
        document.body.classList.remove("is-dragging");
        drag = null;
        // The click that follows the drag must not open the appointment's details.
        block.dataset.justDragged = "1";
        setTimeout(() => delete block.dataset.justDragged, 400);
        if (drop && target) {
            confirmMove(block, target);
        }
    }

    function confirmMove(block, target) {
        const form = document.getElementById("move-form");
        if (!form) {
            return;
        }
        const question = form.dataset.question
            .replace("{who}", block.dataset.moveWho)
            .replace("{when}", target.dataset.dropLabel);
        ask(question, {calm: true}).then(ok => {
            if (!ok) {
                return;
            }
            form.elements.appointmentId.value = block.dataset.moveId;
            form.elements.date.value = target.dataset.dropDate;
            form.elements.time.value = target.dataset.dropTime;
            form.submit();
        });
    }

    function cancelPending() {
        if (pending) {
            clearTimeout(pending.timer);
            pending.block.classList.remove("holding");
            pending = null;
        }
    }

    document.addEventListener("pointerdown", event => {
        const block = event.target.closest("[data-move-id]");
        if (!block || drag || event.button !== 0) {
            return;
        }
        pending = {block, pointerId: event.pointerId, x: event.clientX, y: event.clientY,
            touch: event.pointerType !== "mouse", timer: 0};
        if (pending.touch) {
            block.classList.add("holding"); // sinks while held, so it's clear that holding does something
            pending.timer = setTimeout(() => {
                const start = pending;
                pending = null;
                startDrag(start);
            }, HOLD_MS);
        }
    });

    document.addEventListener("pointermove", event => {
        if (drag && event.pointerId === drag.pointerId) {
            drag.x = event.clientX;
            drag.y = event.clientY;
            follow();
            return;
        }
        if (!pending || event.pointerId !== pending.pointerId) {
            return;
        }
        const moved = Math.hypot(event.clientX - pending.x, event.clientY - pending.y);
        if (pending.touch && moved > TOUCH_SLOP) {
            cancelPending(); // the finger is scrolling the page
        } else if (!pending.touch && moved > MOUSE_SLOP) {
            const start = {...pending, x: event.clientX, y: event.clientY};
            pending = null;
            startDrag(start);
        }
    });

    document.addEventListener("pointerup", event => {
        cancelPending();
        if (drag && event.pointerId === drag.pointerId) {
            endDrag(true);
        }
    });

    document.addEventListener("pointercancel", () => {
        cancelPending();
        if (drag) {
            endDrag(false);
        }
    });

    // While dragging on a touch screen the page must not scroll under the finger.
    document.addEventListener("touchmove", event => {
        if (drag) {
            event.preventDefault();
        }
    }, {passive: false});

    // Holding an appointment shouldn't bring up the phone's link menu.
    document.addEventListener("contextmenu", event => {
        if ((drag || pending) && event.target.closest("[data-move-id]")) {
            event.preventDefault();
        }
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && drag) {
            endDrag(false);
        }
    });

    /*
     * Week on a phone: the day strip jumps to a day and shows which day is at the top; the page lands
     * on the day the server names (data-focus-day: today, or the day just changed).
     */
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const smooth = () => (reducedMotion.matches ? "auto" : "smooth");
    let jumpedTo = null;

    function jumpToDay(date, behavior) {
        const day = document.getElementById("day-" + date);
        if (day) {
            jumpedTo = date;
            day.scrollIntoView({block: "start", behavior});
        }
    }

    document.addEventListener("click", event => {
        const chip = event.target.closest("[data-jump-day]");
        if (chip && !event.ctrlKey && !event.metaKey) {
            event.preventDefault();
            jumpToDay(chip.dataset.day, smooth());
        }
    });

    // Scrolling by hand ends a jump; the jumps' own scrolling (and a drag's) doesn't.
    ["wheel", "touchmove"].forEach(type => window.addEventListener(type, () => {
        if (!drag) {
            jumpedTo = null;
        }
    }, {passive: true}));

    /*
     * The current day: the one jumped to while it is on screen (the last days can't always reach the
     * top), otherwise the last one whose top has gone under the strip.
     */
    function markCurrentDay() {
        const strip = document.querySelector(".day-strip");
        if (!strip?.offsetParent) {
            return;
        }
        const line = strip.getBoundingClientRect().bottom + 12;
        let current = null;
        for (const day of document.querySelectorAll(".week-board > .day-col")) {
            if (day.getBoundingClientRect().top > line) {
                break;
            }
            current = day.id.slice("day-".length);
        }
        const jumped = jumpedTo && document.getElementById("day-" + jumpedTo)?.getBoundingClientRect();
        if (jumped && jumped.top < window.innerHeight && jumped.bottom > line) {
            current = jumpedTo;
        }
        strip.querySelectorAll("[data-jump-day]").forEach(chip => {
            chip.classList.toggle("current", chip.dataset.day === current);
            chip.toggleAttribute("aria-current", chip.dataset.day === current);
        });
    }

    let markQueued = false;
    window.addEventListener("scroll", () => {
        if (!markQueued) {
            markQueued = true;
            requestAnimationFrame(() => {
                markQueued = false;
                markCurrentDay();
            });
        }
    }, {passive: true});

    function landOnFocusDay() {
        const board = document.querySelector(".week-board[data-focus-day]");
        const strip = document.querySelector(".day-strip");
        const navigation = performance.getEntriesByType?.("navigation")[0];
        // Not on a reload or Back (the browser puts the page back where it was), nor past a #day-... link.
        if (board && strip?.offsetParent && !location.hash && (!navigation || navigation.type === "navigate")) {
            jumpToDay(board.dataset.focusDay, "auto");
        }
        markCurrentDay();
    }

    /* Opening a closed day: bring its little form into view (on a phone it opens under the bottom bar). */
    document.addEventListener("toggle", event => {
        const details = event.target;
        if (details.matches?.(".open-day") && details.open) {
            details.querySelector("form")?.scrollIntoView({block: "nearest", behavior: smooth()});
        }
    }, true);

    /*
     * Booking form: the patient dropdown ([data-combo]). Opening it loads the patients booked before
     * (htmx, on "combo-open"); typing searches. Arrow keys move through the list, Enter picks, Escape
     * or a click outside closes. Picking fills phone, name and language (the hint below then confirms
     * the patient) and shows them in the field; typing another phone there clears that.
     */
    function comboParts(combo) {
        return {
            trigger: combo.querySelector("[data-combo-trigger]"),
            panel: combo.querySelector("[data-combo-panel]"),
            search: combo.querySelector("[data-patient-search]"),
            clear: combo.querySelector("[data-combo-clear]"),
            form: combo.closest("form")
        };
    }

    const comboOptions = combo => [...combo.querySelectorAll("[data-pick]")];

    function openCombo(combo) {
        const {trigger, panel, search} = comboParts(combo);
        if (!panel.hidden) {
            return;
        }
        panel.hidden = false;
        trigger.setAttribute("aria-expanded", "true");
        search.focus();
        if (!combo.querySelector("#patient-results > *")) {
            window.htmx?.trigger(search, "combo-open");
        }
    }

    function closeCombo(combo, focusTrigger) {
        const {trigger, panel} = comboParts(combo);
        if (panel.hidden) {
            return;
        }
        panel.hidden = true;
        trigger.setAttribute("aria-expanded", "false");
        if (focusTrigger) {
            trigger.focus();
        }
    }

    function setActive(combo, index) {
        const {search} = comboParts(combo);
        const options = comboOptions(combo);
        options.forEach((option, i) => {
            option.classList.toggle("active", i === index);
            option.setAttribute("aria-selected", String(i === index));
        });
        if (options[index]) {
            search.setAttribute("aria-activedescendant", options[index].id);
            options[index].scrollIntoView({block: "nearest"});
        } else {
            search.removeAttribute("aria-activedescendant");
        }
    }

    /* Shows the chosen patient in the field (copying the option's avatar), or the placeholder for null. */
    function showChosen(combo, chosen) {
        const box = combo.querySelector("[data-combo-chosen]");
        combo.querySelector("[data-combo-empty]").hidden = Boolean(chosen);
        box.hidden = !chosen;
        comboParts(combo).clear.hidden = !chosen;
        if (chosen) {
            const avatar = box.querySelector("[data-chosen-avatar]");
            avatar.textContent = chosen.avatar?.textContent ?? "";
            avatar.style.cssText = chosen.avatar?.style.cssText ?? "";
            box.querySelector("[data-chosen-name]").textContent = chosen.name;
            box.querySelector("[data-chosen-phone]").textContent = chosen.phone;
        }
    }

    function pickPatient(combo, option) {
        const {form} = comboParts(combo);
        form.querySelector("[name=name]").value = option.dataset.name;
        form.querySelectorAll("[name=language]").forEach(radio => (radio.checked = radio.value === option.dataset.language));
        const phone = form.querySelector("[name=phone]");
        phone.value = option.dataset.phone;
        showChosen(combo, {name: option.dataset.name, phone: option.dataset.phone, avatar: option.querySelector(".avatar")});
        phone.dispatchEvent(new Event("input", {bubbles: true}));
        closeCombo(combo, false);
        form.querySelector("button[type=submit]")?.focus();
    }

    /* Marks what was typed in the names found, and makes the first one the one Enter picks. */
    function refreshComboResults(combo) {
        const query = comboParts(combo).search.value.trim().toLocaleLowerCase();
        combo.querySelectorAll("[data-hl]").forEach(name => {
            const text = name.textContent;
            const at = query ? text.toLocaleLowerCase().indexOf(query) : -1;
            if (at < 0) {
                name.textContent = text;
                return;
            }
            const mark = document.createElement("mark");
            mark.textContent = text.slice(at, at + query.length);
            name.replaceChildren(text.slice(0, at), mark, text.slice(at + query.length));
        });
        setActive(combo, comboOptions(combo).length ? 0 : -1);
        // Low on a phone screen the list would open under the bottom bar.
        comboParts(combo).panel.scrollIntoView({block: "nearest"});
    }

    document.addEventListener("click", event => {
        const combo = event.target.closest("[data-combo]");
        if (!combo) {
            return;
        }
        const option = event.target.closest("[data-pick]");
        if (option) {
            pickPatient(combo, option);
        } else if (event.target.closest("[data-combo-trigger]")) {
            comboParts(combo).panel.hidden ? openCombo(combo) : closeCombo(combo, true);
        } else if (event.target.closest("[data-combo-clear]")) {
            const {form, trigger} = comboParts(combo);
            showChosen(combo, null);
            form.querySelector("[name=name]").value = "";
            const phone = form.querySelector("[name=phone]");
            phone.value = "";
            phone.dispatchEvent(new Event("input", {bubbles: true}));
            trigger.focus();
        }
    });

    // Keeps the focus in the search box while an option is clicked (some browsers would move it away).
    document.addEventListener("mousedown", event => {
        if (event.target.closest(".combo-results")) {
            event.preventDefault();
        }
    });

    document.addEventListener("pointerdown", event => {
        document.querySelectorAll("[data-combo]").forEach(combo => {
            if (!combo.contains(event.target)) {
                closeCombo(combo, false);
            }
        });
    });

    document.addEventListener("keydown", event => {
        const combo = event.target.closest?.("[data-combo]");
        if (!combo) {
            return;
        }
        if (event.target.matches("[data-combo-trigger]") && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
            event.preventDefault();
            openCombo(combo);
            return;
        }
        if (!event.target.matches("[data-patient-search]")) {
            return;
        }
        const options = comboOptions(combo);
        const current = options.findIndex(option => option.classList.contains("active"));
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            const step = event.key === "ArrowDown" ? 1 : -1;
            setActive(combo, options.length ? (current + step + options.length) % options.length : -1);
        } else if (event.key === "Enter") {
            event.preventDefault();
            if (options[current]) {
                pickPatient(combo, options[current]);
            }
        } else if (event.key === "Escape") {
            event.preventDefault(); // close the list, not the slide-over around the form
            event.stopPropagation();
            closeCombo(combo, true);
        } else if (event.key === "Tab") {
            closeCombo(combo, false);
        }
    });

    // Escape inside the slide-over: an open list closes first.
    document.addEventListener("cancel", event => {
        const open = [...event.target.querySelectorAll?.("[data-combo]") ?? []].find(combo => !comboParts(combo).panel.hidden);
        if (open) {
            event.preventDefault();
            closeCombo(open, true);
        }
    }, true);

    document.addEventListener("htmx:afterSettle", () => {
        document.querySelectorAll("[data-combo]").forEach(combo => {
            if (!comboParts(combo).panel.hidden) {
                refreshComboResults(combo);
            }
        });
    });

    // Typing a different phone than the chosen patient's means someone else: the field stops showing them.
    document.addEventListener("input", event => {
        const combo = event.target.name === "phone" ? event.target.form?.querySelector("[data-combo]") : null;
        if (!combo || combo.querySelector("[data-combo-chosen]").hidden) {
            return;
        }
        const typed = event.target.value.replace(/\D/g, "").replace(/^0+/, "");
        const chosen = combo.querySelector("[data-chosen-phone]").textContent.replace(/\D/g, "");
        if (typed.length < 6 || !chosen.endsWith(typed)) {
            showChosen(combo, null);
        }
    });

    /* Settings: the WhatsApp Info preview follows the address and hours as they are typed. */
    document.addEventListener("input", event => {
        const field = event.target.closest("[data-preview]");
        if (!field) {
            return;
        }
        const name = field.dataset.preview;
        document.querySelectorAll(`[data-preview-for="${name}"]`).forEach(target => (target.textContent = field.value.trim()));
        document.querySelectorAll(`[data-preview-line="${name}"]`).forEach(line => (line.hidden = !field.value.trim()));
        const anyFilled = [...document.querySelectorAll("[data-preview]")].some(other => other.value.trim());
        document.querySelectorAll("[data-preview-empty]").forEach(empty => (empty.hidden = anyFilled));
    });

    /* A GET filter form with data-clean-query leaves its empty fields out of the address htmx pushes. */
    document.addEventListener("htmx:configRequest", event => {
        const formData = event.detail.formData;
        if (event.detail.verb === "get" && event.detail.elt.matches?.("[data-clean-query]") && formData) {
            [...formData.keys()].filter(key => formData.get(key) === "").forEach(key => formData.delete(key));
        }
    });

    /* <input type="date" data-navigate-date="/?date=DATE"> goes to that day when picked. */
    document.addEventListener("change", event => {
        const input = event.target.closest("[data-navigate-date]");
        if (input && input.value) {
            window.location.href = input.dataset.navigateDate.replace("DATE", input.value);
        }
    });

    document.addEventListener("DOMContentLoaded", () => {
        markTheme();
        markSidebar();
        initToasts();
        landOnFocusDay();
    });
    document.addEventListener("htmx:afterSettle", initToasts);
})();
