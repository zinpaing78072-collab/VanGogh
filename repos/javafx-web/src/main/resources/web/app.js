function onJavaReady() {
    append('Java bridge ready. javaApp = ' + (typeof javaApp));
}

function callJava() {
    const reply = javaApp.greet('WebView');
    append(reply);
    javaApp.log('greet returned: ' + reply);
}

function log(message) {
    javaApp.log(message);
    append(message);
}

function append(text) {
    const el = document.getElementById('log');
    el.textContent += text + '\n';
}
