DataTableColumnHeaderUI.extendMenu(function (column, columnHeaderUI, menu) {
  menu.push({});   // separator
  menu.push({
    id: "files-ext-trieshake",
    label: "Trieshake",
    submenu: [
      {
        id: "files-ext/trieshake-forward",
        label: "Trieshake path column\u2026",
        click: function () { new TrieshakeDialog(column, "forward"); }
      },
      {
        id: "files-ext/trieshake-reverse",
        label: "Reverse trieshake\u2026",
        click: function () { new TrieshakeDialog(column, "reverse"); }
      }
    ]
  });
});
