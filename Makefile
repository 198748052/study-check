SHELL := /bin/bash
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo 1.1.0)
LDFLAGS := -s -w -X main.version=$(VERSION)
ANDROID_HOME ?= $(HOME)/Android/Sdk
JAVA_HOME ?= /usr/lib/jvm/java-17-openjdk-amd64

.PHONY: help build build-linux run dev apk apk-release deploy clean

help:
	@echo "make build        构建当前平台单二进制 server/studycheck"
	@echo "make build-linux  交叉编译 linux/amd64（CI 用）"
	@echo "make run          直接跑已构建的二进制"
	@echo "make dev          本地开发：go run（:8080）"
	@echo "make apk          编译安卓 debug APK（可安装）"
	@echo "make apk-release  编译安卓 release APK（未签名/或按 gradle.properties 签名）"
	@echo "make deploy       一键部署到服务器（需先配置 deploy/deploy.env）"

build:
	cd server && CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)" -o studycheck .

build-linux:
	cd server && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "$(LDFLAGS)" -o studycheck-linux-amd64 .

dev:
	cd server && SC_DB=../data/dev.db SC_UPLOADS=../data/uploads go run .

run:
	cd server && ./studycheck

apk:
	cd android && JAVA_HOME=$(JAVA_HOME) ANDROID_HOME=$(ANDROID_HOME) ./gradlew assembleDebug
	@echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"

apk-release:
	cd android && JAVA_HOME=$(JAVA_HOME) ANDROID_HOME=$(ANDROID_HOME) ./gradlew assembleRelease
	@echo "APK: android/app/build/outputs/apk/release/"

deploy:
	@test -f deploy/deploy.env || { echo "请先 cp deploy/deploy.env.example deploy/deploy.env 并填写"; exit 1; }
	@bash deploy/deploy.sh

clean:
	rm -rf server/studycheck server/studycheck-linux-amd64
	cd android && ./gradlew clean || true
