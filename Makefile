EXTENSION_DIR = extension/module/MOD-INF/lib
INSTALL_DIR = $(HOME)/Library/Application Support/OpenRefine/extensions/files-ext

.PHONY: jar test extension install clean zip

jar:
	lein uberjar

test:
	lein test

extension: jar
	mkdir -p $(EXTENSION_DIR)
	cp target/files-ext.jar $(EXTENSION_DIR)/files-ext.jar

install: extension
	rm -rf "$(INSTALL_DIR)"
	mkdir -p "$(INSTALL_DIR)"
	cp -R extension/module/ "$(INSTALL_DIR)/"

clean:
	lein clean
	rm -f $(EXTENSION_DIR)/files-ext.jar

zip: extension
	mkdir -p dist/files-ext
	cp -R extension/module/ dist/files-ext/
	cd dist && zip -r files-ext.zip files-ext
	rm -rf dist/files-ext
