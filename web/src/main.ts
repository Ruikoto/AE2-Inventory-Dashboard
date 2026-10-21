import { createApp } from "vue";
import App from "./views/App.vue";
import "./style.css";
import { applyTheme, loadTheme } from "./models/theme";
applyTheme(loadTheme());
createApp(App).mount("#app");
