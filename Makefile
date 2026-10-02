# FlightSignal developer commands. Run `make help` for the list.

SHELL := /bin/sh

# Optional local overrides (copy .env.example to .env).
-include .env
POSTGRES_USER ?= flights
POSTGRES_DB ?= flights
export SIM_STEP_SECONDS SIM_PAUSE_SECONDS SIM_PASSENGERS SIM_CYCLES

# Java 25: the first candidate that really is Java 25 wins - JAVA_HOME, the
# macOS java_home registry, Homebrew (Apple silicon / Intel), common Linux paths.
# Override with: make JAVA_25_HOME=/path/to/jdk-25 <target>
JAVA_25_CANDIDATES = "$$JAVA_HOME" \
  "$$( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 25 2>/dev/null )" \
  /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  /usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  /usr/lib/jvm/temurin-25-jdk* /usr/lib/jvm/java-25-openjdk*
JAVA_25_HOME ?= $(shell for c in $(JAVA_25_CANDIDATES); do \
  if [ -n "$$c" ] && [ -x "$$c/bin/java" ] \
     && "$$c/bin/java" -version 2>&1 | grep -q 'version "25'; then echo "$$c"; break; fi; \
  done)
MVNW := $(if $(JAVA_25_HOME),env JAVA_HOME="$(JAVA_25_HOME)") ./mvnw -B
COMPOSE := docker compose
PSQL_CMD := $(COMPOSE) exec -T postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)
PSQL := $(PSQL_CMD) -v ON_ERROR_STOP=1

.DEFAULT_GOAL := help
.PHONY: help setup install db-up db-down db-reset db-seed dev dev-api dev-web \
        test test-api test-web verify lint build build-api build-web \
        up down logs simulate simulate-clean check-java

help: ## Show available commands
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## ---- Local development (processes on your machine, Postgres in Docker) ----

setup: install db-up ## Install frontend dependencies and start PostgreSQL

install: ## Install frontend dependencies
	npm install

db-up: ## Start PostgreSQL
	$(COMPOSE) up -d --wait postgres

db-down: ## Stop all containers (data is kept)
	$(COMPOSE) --profile app --profile sim down

db-reset: ## Stop containers and DELETE the database volume
	$(COMPOSE) --profile app --profile sim down -v

db-seed: ## Re-apply demo flights and passengers to a running database
	$(PSQL) -f /workspace/migrations/seed/R__1_seed_flights.sql
	$(PSQL) -f /workspace/migrations/seed/R__2_seed_passengers.sql

dev: ## Run the API (dev profile) and the Vite UI together
	npm run dev

dev-api: check-java ## Run only the Spring Boot API (Flyway migrates + seeds)
	cd server && $(MVNW) spring-boot:run -Dspring-boot.run.profiles=dev

dev-web: ## Run only the Vite dev server
	npm run dev -w web

## ---- Quality ----

test: test-api test-web ## Unit tests (backend + frontend)

test-api: check-java ## Backend unit tests
	cd server && $(MVNW) test

test-web: ## Frontend lint + unit tests
	npm run lint -w web
	npm run test -w web

verify: check-java ## Backend unit + Testcontainers integration tests (needs Docker)
	cd server && $(MVNW) verify

lint: ## Lint the frontend
	npm run lint -w web

build: build-api build-web ## Build the API jar and the web bundle

build-api: check-java
	cd server && $(MVNW) package -DskipTests

build-web:
	npm run build -w web

## ---- Containers (everything in Docker) ----

up: ## Build and start Postgres + API + web UI (http://localhost:8080)
	$(COMPOSE) --profile app up --build -d --wait

down: ## Stop the container stack
	$(COMPOSE) --profile app --profile sim down

logs: ## Follow API and web logs
	$(COMPOSE) --profile app logs -f api web

## ---- Simulation ----

simulate: ## Continuously create flights + passengers and disrupt them (Ctrl+C to stop)
	@curl --fail --silent http://localhost:$(or $(API_PORT),3101)/actuator/health/liveness >/dev/null \
	  || (echo "The API is not reachable on port $(or $(API_PORT),3101). Start it with 'make dev' or 'make up' first."; exit 1)
	@PSQL='$(PSQL_CMD)' sh scripts/simulate.sh

simulate-clean: ## Remove all simulated flights, passengers, and their events
	$(PSQL) -q < db/simulator/install.sql
	$(PSQL) -c "CALL sim.cleanup()"

check-java:
	@if [ -z "$(JAVA_25_HOME)" ]; then \
	  echo "Java 25 was not found (checked JAVA_HOME=$${JAVA_HOME:-<unset>}, /usr/libexec/java_home -v 25, Homebrew openjdk@25)."; \
	  echo "Install it (e.g. 'brew install openjdk@25') or run: make JAVA_25_HOME=/path/to/jdk-25 <target>"; \
	  exit 1; \
	fi
	@echo "Using Java 25 from $(JAVA_25_HOME)"
