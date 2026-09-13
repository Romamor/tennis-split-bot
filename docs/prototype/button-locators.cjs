// Navigation tests accept a decorative leading emoji; visual checks assert the icons separately.
exports.buttonName=name=>new RegExp('^(?:\\p{Extended_Pictographic}\\uFE0F?\\s+)?'+name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'$','u');
