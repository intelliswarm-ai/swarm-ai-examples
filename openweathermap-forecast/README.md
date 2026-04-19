# OpenWeatherMap Forecast Example

Exercises **`OpenWeatherMapTool`** — a travel-planner agent translates current conditions + the
5-day / 3-hour forecast into concrete packing advice for a given city.

## Prerequisites

**API key (required):**

| Env var                | How to get it                                                    |
|------------------------|------------------------------------------------------------------|
| `OPENWEATHER_API_KEY`  | Free tier at https://openweathermap.org/api (60 req/min)         |

```bash
export OPENWEATHER_API_KEY=your-api-key-here
```

> New keys can take up to 2 hours to activate — the tool surfaces 401 responses with that hint.

**Infrastructure:** none — calls go to `api.openweathermap.org`.

## Run

```bash
./run.sh weather                       # Zurich,CH (default)
./run.sh weather "London,GB"
./run.sh weather "Tokyo"
./run.sh weather "New York,US"
```

## What this proves about the tool

- `operation=current` returns name, country, temp/feels-like, humidity, wind, sunrise/sunset — all
  with units appropriate to `units=metric|imperial|standard`.
- `operation=forecast` aggregates 3-hour entries into daily min/max + representative condition.
- Invalid city names surface a clean `location not found` message.
- Lat/lon coords are accepted as an alternative to city name (sanity-checked against
  [-90,90] × [-180,180]).
- Sunrise/sunset are rendered in the target city's local time (not UTC).
