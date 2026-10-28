// Fishbowl Seerr (Overseerr / Jellyseerr) Request & Discovery Engine
// Native Node.js Bionic Service on Port 5055
// High-Fidelity Desktop & Mobile Responsive Control Plane & Comprehensive REST API

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const url = require('url');
const net = require('net');

process.env.OPENSSL_CONF = '/dev/null';
if (!process.env.SSL_CERT_FILE) {
    const certCandidates = [
        '/data/data/com.fishbowl.app/files/usr/etc/tls/cert.pem',
        '/data/user/0/com.fishbowl.app/files/usr/etc/tls/cert.pem'
    ];
    for (const cp of certCandidates) {
        if (fs.existsSync(cp)) { process.env.SSL_CERT_FILE = cp; break; }
    }
}

const PORT = parseInt(process.env.PORT || '5055', 10);
const HOME_DIR = process.env.HOME || '/data/data/com.fishbowl.app/files/home';
const CONFIG_DIR = process.env.CONFIG_DIRECTORY || path.join(HOME_DIR, '.config/seerr');

if (!fs.existsSync(CONFIG_DIR)) {
    try { fs.mkdirSync(CONFIG_DIR, { recursive: true }); } catch (e) {}
}

// ─────────────────────────────────────────────────────────────────────────────
// CONFIG & API KEY UTILITIES
// ─────────────────────────────────────────────────────────────────────────────

function readXmlApiKey(serviceName) {
    const candidates = [
        path.join(HOME_DIR, '.config', serviceName, 'config.xml'),
        path.join(HOME_DIR, 'files/home/.config', serviceName, 'config.xml'),
        `/data/data/com.fishbowl.app/files/home/.config/${serviceName}/config.xml`,
        `/data/user/0/com.fishbowl.app/files/home/.config/${serviceName}/config.xml`
    ];
    for (const configPath of candidates) {
        try {
            if (fs.existsSync(configPath)) {
                const content = fs.readFileSync(configPath, 'utf8');
                const match = content.match(/<ApiKey>([a-zA-Z0-9_-]+)<\/ApiKey>/i);
                if (match && match[1]) return match[1];
            }
        } catch (e) {}
    }
    return null;
}

function getStackKeys() {
    return {
        radarr: readXmlApiKey('Radarr'),
        sonarr: readXmlApiKey('Sonarr'),
        prowlarr: readXmlApiKey('Prowlarr')
    };
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTP CLIENT HELPERS
// ─────────────────────────────────────────────────────────────────────────────

function fetchJson(targetUrl, options = {}) {
    return new Promise((resolve, reject) => {
        const parsed = url.parse(targetUrl);
        const isHttps = parsed.protocol === 'https:';
        const client = isHttps ? https : http;

        const headers = Object.assign({}, options.headers || {});
        let bodyData = null;
        if (options.body) {
            bodyData = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
            if (!headers['Content-Type']) headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(bodyData);
        }

        const redirectCount = options._redirectCount || 0;
        const req = client.request({
            protocol: parsed.protocol,
            hostname: parsed.hostname,
            port: parsed.port || (isHttps ? 443 : 80),
            path: parsed.path,
            method: options.method || 'GET',
            headers: headers,
            timeout: options.timeout || 6000
        }, (res) => {
            if ((res.statusCode === 301 || res.statusCode === 302 || res.statusCode === 307 || res.statusCode === 308) && res.headers.location && redirectCount < 4) {
                const redirectUrl = url.resolve(targetUrl, res.headers.location);
                return fetchJson(redirectUrl, Object.assign({}, options, { _redirectCount: redirectCount + 1 })).then(resolve, reject);
            }
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    try {
                        resolve(JSON.parse(data));
                    } catch (e) {
                        resolve(data);
                    }
                } else {
                    reject(new Error(`HTTP ${res.statusCode}: ${data.substring(0, 200)}`));
                }
            });
        });

        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('Request timed out'));
        });

        if (bodyData) req.write(bodyData);
        req.end();
    });
}

function probePort(port, pathStr = '/') {
    return new Promise((resolve) => {
        const req = http.request({
            hostname: '127.0.0.1',
            port: port,
            path: pathStr,
            method: 'GET',
            timeout: 2000
        }, res => {
            resolve(res.statusCode < 500);
        });
        req.on('error', () => resolve(false));
        req.on('timeout', () => { req.destroy(); resolve(false); });
        req.end();
    });
}

// ─────────────────────────────────────────────────────────────────────────────
// RADARR & SONARR INTEGRATION HELPERS
// ─────────────────────────────────────────────────────────────────────────────

async function getRadarrQualityProfiles(apiKey) {
    try {
        const profiles = await fetchJson(`http://127.0.0.1:7878/api/v3/qualityprofile?apiKey=${apiKey}`);
        if (Array.isArray(profiles) && profiles.length > 0) return profiles;
    } catch (e) {}
    return [
        { id: 4, name: 'HD-1080p' },
        { id: 5, name: 'Ultra-HD (4K)' },
        { id: 1, name: 'Any' },
        { id: 3, name: 'HD-720p' }
    ];
}

async function getSonarrQualityProfiles(apiKey) {
    try {
        const profiles = await fetchJson(`http://127.0.0.1:8989/api/v3/qualityprofile?apiKey=${apiKey}`);
        if (Array.isArray(profiles) && profiles.length > 0) return profiles;
    } catch (e) {}
    return [
        { id: 4, name: 'HD-1080p' },
        { id: 5, name: 'Ultra-HD (4K)' },
        { id: 1, name: 'Any' },
        { id: 3, name: 'HD-720p' }
    ];
}

async function getRadarrRootFolders(apiKey) {
    try {
        const folders = await fetchJson(`http://127.0.0.1:7878/api/v3/rootfolder?apiKey=${apiKey}`);
        if (Array.isArray(folders) && folders.length > 0) {
            return folders.filter(f => f.accessible && f.path).map(f => f.path);
        }
    } catch (e) {}
    return ['/storage/emulated/0/FishbowlMedia/Movies'];
}

async function getSonarrRootFolders(apiKey) {
    try {
        const folders = await fetchJson(`http://127.0.0.1:8989/api/v3/rootfolder?apiKey=${apiKey}`);
        if (Array.isArray(folders) && folders.length > 0) {
            return folders.filter(f => f.accessible && f.path).map(f => f.path);
        }
    } catch (e) {}
    return ['/storage/emulated/0/FishbowlMedia/TV'];
}

async function getRadarrExistingMovies(apiKey) {
    try {
        const movies = await fetchJson(`http://127.0.0.1:7878/api/v3/movie?apiKey=${apiKey}`);
        if (Array.isArray(movies)) return movies;
    } catch (e) {}
    return [];
}

async function getSonarrExistingSeries(apiKey) {
    try {
        const series = await fetchJson(`http://127.0.0.1:8989/api/v3/series?apiKey=${apiKey}`);
        if (Array.isArray(series)) return series;
    } catch (e) {}
    return [];
}


async function cancelRadarrMovie(tmdbId, title, apiKey) {
    try {
        const movies = await getRadarrExistingMovies(apiKey);
        const match = movies.find(m => (tmdbId && m.tmdbId === tmdbId) || (title && m.title && m.title.toLowerCase() === title.toLowerCase()));
        if (match && match.id) {
            await fetchJson(`http://127.0.0.1:7878/api/v3/movie/${match.id}?deleteFiles=false&addImportExclusion=false&apiKey=${apiKey}`, {
                method: 'DELETE'
            });
            return true;
        }
    } catch (e) {}
    return false;
}

async function cancelSonarrSeries(tvdbId, title, apiKey) {
    try {
        const series = await getSonarrExistingSeries(apiKey);
        const match = series.find(s => (tvdbId && s.tvdbId === tvdbId) || (title && s.title && s.title.toLowerCase() === title.toLowerCase()));
        if (match && match.id) {
            await fetchJson(`http://127.0.0.1:8989/api/v3/series/${match.id}?deleteFiles=false&addImportListExclusion=false&apiKey=${apiKey}`, {
                method: 'DELETE'
            });
            return true;
        }
    } catch (e) {}
    return false;
}

// ─────────────────────────────────────────────────────────────────────────────
// TRANSMISSION RPC CLIENT
// ─────────────────────────────────────────────────────────────────────────────

let transmissionSessionId = null;

function sendTransmissionRpc(method, args) {
    const payload = JSON.stringify({
        method: method,
        arguments: args || {}
    });

    const sendRpc = (sid) => new Promise((resolve, reject) => {
        const headers = { 'Content-Type': 'application/json' };
        if (sid) headers['X-Transmission-Session-Id'] = sid;

        const req = http.request({
            hostname: '127.0.0.1',
            port: 9091,
            path: '/transmission/rpc',
            method: 'POST',
            headers: headers,
            timeout: 4000
        }, (res) => {
            if (res.statusCode === 409) {
                const newSid = res.headers['x-transmission-session-id'];
                if (newSid) {
                    transmissionSessionId = newSid;
                    resolve(sendRpc(newSid));
                    return;
                }
            }
            let data = '';
            res.on('data', c => data += c);
            res.on('end', () => {
                try {
                    resolve(JSON.parse(data));
                } catch (e) {
                    resolve(null);
                }
            });
        });

        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        req.write(payload);
        req.end();
    });

    return sendRpc(transmissionSessionId).catch(() => null);
}

async function getTransmissionTorrents() {
    try {
        const res = await sendTransmissionRpc('torrent-get', {
            fields: ['id', 'name', 'percentDone', 'rateDownload', 'rateUpload', 'status', 'totalSize', 'eta', 'errorString', 'downloadDir']
        });
        return (res && res.arguments && res.arguments.torrents) ? res.arguments.torrents : [];
    } catch (e) {
        return [];
    }
}

async function pauseTransmissionTorrent(id) {
    const res = await sendTransmissionRpc('torrent-stop', { ids: [Number(id)] });
    return res && res.result === 'success';
}

async function resumeTransmissionTorrent(id) {
    const res = await sendTransmissionRpc('torrent-start', { ids: [Number(id)] });
    return res && res.result === 'success';
}

async function removeTransmissionTorrent(id, deleteData = true) {
    const res = await sendTransmissionRpc('torrent-remove', { ids: [Number(id)], 'delete-local-data': !!deleteData });
    return res && res.result === 'success';
}

// ─────────────────────────────────────────────────────────────────────────────
// CURATED MODERN MEDIA CATALOG (2024–2026 POPULAR & TRENDING)
// ─────────────────────────────────────────────────────────────────────────────

const CURATED_MEDIA = [
    {
        "type": "movie",
        "title": "Dune: Part Two",
        "year": 2024,
        "tmdbId": 693134,
        "overview": "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family. Facing a choice between the love of his life and the fate of the known universe, he endeavors to prevent a terrible future.",
        "rating": 8.6,
        "runtime": "2h 46m",
        "certification": "PG-13",
        "genres": [
            "Science Fiction",
            "Adventure"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/3HzGtM0JpfH2pWFGugJK22LRP6b.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/eZ239CUp1d6OryZEBPnO2n87gMG.jpg"
    },
    {
        "type": "movie",
        "title": "Deadpool & Wolverine",
        "year": 2024,
        "tmdbId": 533535,
        "overview": "A listless Wade Wilson toils away in civilian life. But when his homeworld faces an existential threat, Wade must reluctantly suit-up again with an even more reluctant Wolverine.",
        "rating": 8,
        "runtime": "2h 07m",
        "certification": "R",
        "genres": [
            "Action",
            "Comedy"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/yDHYTfA3R0jFYba16jBB1jv8uqC.jpg"
    },
    {
        "type": "movie",
        "title": "Gladiator II",
        "year": 2024,
        "tmdbId": 558449,
        "overview": "Years after witnessing the death of the revered hero Maximus at the hands of his uncle, Lucius must enter the Colosseum after his home is conquered by the tyrannical Emperors who now lead Rome.",
        "rating": 7.8,
        "runtime": "2h 28m",
        "certification": "R",
        "genres": [
            "Action",
            "Adventure",
            "Drama"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/euYIwmqkmz95mnXvufEmbL69mZ9.jpg"
    },
    {
        "type": "movie",
        "title": "Wicked",
        "year": 2024,
        "tmdbId": 402431,
        "overview": "Elphaba, an ostracized but gifted young woman born with green skin, and Glinda, a privileged and ambitious girl, meet as students at Shiz University in the Land of Oz and forge an unlikely but profound friendship.",
        "rating": 8.2,
        "runtime": "2h 40m",
        "certification": "PG",
        "genres": [
            "Drama",
            "Fantasy",
            "Romance"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/xDGbZ0JJ3mYaGKy4Nzd9Kph6M9L.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/uVl9iE2VDUuXqrhB72ASt5zYWUd.jpg"
    },
    {
        "type": "movie",
        "title": "Alien: Romulus",
        "year": 2024,
        "tmdbId": 945961,
        "overview": "While scavenging the deep ends of a derelict space station, a group of young space colonizers come face to face with the most terrifying life form in the universe.",
        "rating": 7.7,
        "runtime": "1h 59m",
        "certification": "R",
        "genres": [
            "Horror",
            "Science Fiction"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/2uSWRTtCG336nuBiG8jOTEUKSy8.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/iYqSQaWDttQIQzsxg9xHyg0bttG.jpg"
    },
    {
        "type": "movie",
        "title": "Moana 2",
        "year": 2024,
        "tmdbId": 1241982,
        "overview": "After receiving an unexpected call from her wayfinding ancestors, Moana journeys to the far seas of Oceania and into dangerous, long-lost waters for an adventure unlike anything she has ever faced.",
        "rating": 7.6,
        "runtime": "1h 40m",
        "certification": "PG",
        "genres": [
            "Animation",
            "Adventure",
            "Family"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/aLVkiINlIeCkcZIzb7XHzPYgO6L.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/vYqt6kb4lcF8wwqsMMaULkP9OEn.jpg"
    },
    {
        "type": "movie",
        "title": "Thunderbolts*",
        "year": 2025,
        "tmdbId": 774370,
        "overview": "An irreverent team of Marvel antiheroes and reformed villains, including Yelena Belova, Bucky Barnes, and Red Guardian, embark on covert missions for the government.",
        "rating": 8.3,
        "runtime": "2h 15m",
        "certification": "PG-13",
        "genres": [
            "Action",
            "Science Fiction"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/hqcexYHbiTBfDIdDWxrxPtVndBX.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/rthMuZfFv4fqEU4JVbgSW9wQ8rs.jpg"
    },
    {
        "type": "movie",
        "title": "Captain America: Brave New World",
        "year": 2025,
        "tmdbId": 823464,
        "overview": "Sam Wilson finds himself in the middle of an international incident after meeting with newly elected U.S. President Thaddeus Ross, and must uncover a nefarious global conspiracy.",
        "rating": 8.1,
        "runtime": "2h 18m",
        "certification": "PG-13",
        "genres": [
            "Action",
            "Thriller"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/pzIddUEMWhWzfvLI3TwxUG2wGoi.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/ce3prrjh9ZehEl5JinNqr4jIeaB.jpg"
    },
    {
        "type": "movie",
        "title": "Superman",
        "year": 2025,
        "tmdbId": 1064213,
        "overview": "Superman reconciles his heritage with his human upbringing. He is the embodiment of truth, justice, and the American way, guided by human kindness in a world that sees kindness as old-fashioned.",
        "rating": 8.5,
        "runtime": "2h 25m",
        "certification": "PG-13",
        "genres": [
            "Action",
            "Science Fiction",
            "Adventure"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/ldyfo0BKmz5rWtJJKCvwaNS4cJT.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/yRBc6WY3r1Fz5Cjd6DhSvzqunED.jpg"
    },
    {
        "type": "movie",
        "title": "Mission: Impossible - The Final Reckoning",
        "year": 2025,
        "tmdbId": 575265,
        "overview": "Ethan Hunt and his IMF team face their ultimate mission against the sentient AI Entity, racing across oceans and continents to protect humanity from digital oblivion.",
        "rating": 8.8,
        "runtime": "2h 45m",
        "certification": "PG-13",
        "genres": [
            "Action",
            "Thriller",
            "Adventure"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/iKPsC9EFUafRP9SrUznI61getVP.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/xPNDRM50a58uvv1il2GVZrtWjkZ.jpg"
    },
    {
        "type": "movie",
        "title": "Avatar: Fire and Ash",
        "year": 2025,
        "tmdbId": 83533,
        "overview": "Jake Sully and Neytiri encounter the Ash People, a volcanic clan of Na'vi led by Varang, pushing the boundaries of Pandora into unprecedented fiery conflicts.",
        "rating": 8.7,
        "runtime": "3h 10m",
        "certification": "PG-13",
        "genres": [
            "Science Fiction",
            "Adventure",
            "Action"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/s16H6tpK2utvwDtzZ8Qy4qm5Emw.jpg"
    },
    {
        "type": "movie",
        "title": "The Fantastic Four: First Steps",
        "year": 2025,
        "tmdbId": 617126,
        "overview": "In a retro-futuristic 1960s universe, Marvel's first family must defend Earth from the devourer of worlds, Galactus, and his enigmatic herald, the Silver Surfer.",
        "rating": 8.2,
        "runtime": "2h 20m",
        "certification": "PG-13",
        "genres": [
            "Science Fiction",
            "Action",
            "Adventure"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/Ona6r0Aq8Gr41KBGztBibTyrYn.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/s94NjfKkcSczZ1FembwmQZwsuwY.jpg"
    },
    {
        "type": "movie",
        "title": "Nosferatu",
        "year": 2024,
        "tmdbId": 426063,
        "overview": "A gothic tale of obsession between a haunted young woman in 19th century Germany and the ancient Transylvanian vampire who stalks her, bringing untold horror with him.",
        "rating": 7.9,
        "runtime": "2h 12m",
        "certification": "R",
        "genres": [
            "Horror",
            "Drama"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/d0B8xDdqBZlzlPEzES8lu5LlNQn.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/gprjiZWY43vxSKngMha1wfb5TGG.jpg"
    },
    {
        "type": "movie",
        "title": "The Substance",
        "year": 2024,
        "tmdbId": 933260,
        "overview": "A fading celebrity uses a black-market drug that produces a younger, better version of herself, leading to unexpected, grotesque body horror consequences.",
        "rating": 7.8,
        "runtime": "2h 21m",
        "certification": "R",
        "genres": [
            "Horror",
            "Drama",
            "Science Fiction"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/lqoMzCcZYEFK729d6qzt349fB4o.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/bVSOgrxasVJF6V71T7v2KfBRSzu.jpg"
    },
    {
        "type": "movie",
        "title": "Beetlejuice Beetlejuice",
        "year": 2024,
        "tmdbId": 917496,
        "overview": "After a family tragedy, three generations of the Deetz family return home to Winter River. Still haunted by Beetlejuice, Lydia's life is turned upside down when her teenage daughter opens the portal to the Afterlife.",
        "rating": 7.5,
        "runtime": "1h 45m",
        "certification": "PG-13",
        "genres": [
            "Comedy",
            "Fantasy",
            "Horror"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/kKgQzkUCnQmeTPkyIwHly2t6ZFI.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/A1dZ6faTjg0e6HYftBmEKujuXGQ.jpg"
    },
    {
        "type": "movie",
        "title": "Inside Out 2",
        "year": 2024,
        "tmdbId": 1022789,
        "overview": "Teenager Riley's mind undergoes sudden headquarters demolition to make room for brand new emotions: Anxiety, Envy, Ennui, and Embarrassment.",
        "rating": 8.1,
        "runtime": "1h 36m",
        "certification": "PG",
        "genres": [
            "Animation",
            "Comedy",
            "Family"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/vpnVM9B6NMmQpWeZvzLvDESb2QY.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/stKGOm80Z06H6T0455B77R94J.jpg"
    },
    {
        "type": "movie",
        "title": "Oppenheimer",
        "year": 2023,
        "tmdbId": 872585,
        "overview": "The story of J. Robert Oppenheimer's role in the development of the atomic bomb during World War II and the political fallout that followed.",
        "rating": 8.9,
        "runtime": "3h 00m",
        "certification": "R",
        "genres": [
            "Drama",
            "History"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg"
    },
    {
        "type": "movie",
        "title": "Interstellar",
        "year": 2014,
        "tmdbId": 157336,
        "overview": "When Earth becomes uninhabitable, a team of explorers undertakes the most important mission in human history: traveling beyond this galaxy to discover whether mankind has a future among the stars.",
        "rating": 8.7,
        "runtime": "2h 49m",
        "certification": "PG-13",
        "genres": [
            "Science Fiction",
            "Drama",
            "Adventure"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/rAiYTrKGqDCRIIqo664sY9XZIvQ.jpg"
    },
    {
        "type": "tv",
        "title": "Severance",
        "year": 2025,
        "tvdbId": 403254,
        "overview": "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives. In Season 2, the innies face the harrowing consequences of breaching the perimeter.",
        "rating": 8.8,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Drama",
            "Mystery",
            "Sci-Fi"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/fAzHg1AB7ZleOnnxip85DNu165d.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/ixgFmf1X59PUZam2qbAfskx2gQr.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 9
            },
            {
                "seasonNumber": 2,
                "episodeCount": 10
            }
        ]
    },
    {
        "type": "tv",
        "title": "The Last of Us",
        "year": 2025,
        "tvdbId": 392256,
        "overview": "Years after their perilous journey across pandemic America, Joel and Ellie face retribution and the brutal complexities of humanity in an unrelenting post-apocalyptic world.",
        "rating": 8.9,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Action",
            "Adventure",
            "Drama"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/dmo6TYuuJgaYinXBPjrgG9mB5od.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/lY2DhbA7Hy44fAKddr06UrXWWaQ.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 9
            },
            {
                "seasonNumber": 2,
                "episodeCount": 7
            }
        ]
    },
    {
        "type": "tv",
        "title": "Stranger Things",
        "year": 2025,
        "tvdbId": 305288,
        "overview": "In the epic final season, Hawkins is fractured by the Upside Down. Eleven and her friends must rally for the decisive battle against Vecna to save their world.",
        "rating": 8.7,
        "runtime": "Series",
        "certification": "TV-14",
        "genres": [
            "Drama",
            "Sci-Fi",
            "Horror"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 8
            },
            {
                "seasonNumber": 2,
                "episodeCount": 9
            },
            {
                "seasonNumber": 3,
                "episodeCount": 8
            },
            {
                "seasonNumber": 4,
                "episodeCount": 9
            },
            {
                "seasonNumber": 5,
                "episodeCount": 8
            }
        ]
    },
    {
        "type": "tv",
        "title": "Squid Game",
        "year": 2025,
        "tvdbId": 383275,
        "overview": "Three years after winning Squid Game, Player 456 gave up going to the states and is back with a new resolution in his mind: bringing down the sinister games from within.",
        "rating": 8.5,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Action",
            "Mystery",
            "Thriller"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/iE21DSI3n5vI6v1W2HT4feKoM97.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/2meX1nMdScFOoV4370rqHWKmXhY.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 9
            },
            {
                "seasonNumber": 2,
                "episodeCount": 6
            },
            {
                "seasonNumber": 3,
                "episodeCount": 6
            }
        ]
    },
    {
        "type": "tv",
        "title": "Shōgun",
        "year": 2024,
        "tvdbId": 365285,
        "overview": "In 1600 Japan, Lord Yoshii Toranaga fights for his life as his enemies on the Council of Regents unite against him, while an English pilot shipwrecks with valuable secrets.",
        "rating": 8.7,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Drama",
            "History",
            "War"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/7O4iVfOMQmdCSxhOg1WnzG1AgYT.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/bwSmgmd90hCWwqOKQYTEraeOZhJ.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 10
            }
        ]
    },
    {
        "type": "tv",
        "title": "Fallout",
        "year": 2024,
        "tvdbId": 385566,
        "overview": "In a future, post-apocalyptic Los Angeles brought about by nuclear decimation, citizens must live in underground bunkers to protect themselves from radiation, mutants and bandits.",
        "rating": 8.6,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Action",
            "Adventure",
            "Sci-Fi"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/hE0aykUfKqpEFFgB7H9AUGZn15M.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/coaPCIqQBPUZsOnJcWZxhaORcDT.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 8
            }
        ]
    },
    {
        "type": "tv",
        "title": "The Penguin",
        "year": 2024,
        "tvdbId": 410427,
        "overview": "Following the events of The Batman, Oz Cobb makes a play to seize the reins of the criminal underworld in Gotham City following the demise of Carmine Falcone.",
        "rating": 8.8,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Crime",
            "Drama"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/vOWcqC4oDQws1doDWLO7d3dh5qc.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/4TdmuuwiIiKw3JOjIuhdgYxRXnN.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 8
            }
        ]
    },
    {
        "type": "tv",
        "title": "The Bear",
        "year": 2024,
        "tvdbId": 412597,
        "overview": "A young chef from the fine dining world comes home to Chicago to run his family Italian beef sandwich shop after a heartbreaking death.",
        "rating": 8.8,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Comedy",
            "Drama"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/eKfVzzEazSIjJMrw9ADa2x8ksLz.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/aJtG4txtmiRHwAAqENQHZvBs6kY.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 8
            },
            {
                "seasonNumber": 2,
                "episodeCount": 10
            },
            {
                "seasonNumber": 3,
                "episodeCount": 10
            }
        ]
    },
    {
        "type": "tv",
        "title": "House of the Dragon",
        "year": 2024,
        "tvdbId": 371572,
        "overview": "The Targaryen civil war, known as the Dance of the Dragons, reaches its bloody zenith as Rhaenyra and Aegon II vie for the Iron Throne.",
        "rating": 8.6,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Action",
            "Adventure",
            "Drama",
            "Fantasy"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/1X4h40fcB4WWUmIBK0auT4zRBAV.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/etjA69A9Gh0ETummsL3hhni1g7b.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 10
            },
            {
                "seasonNumber": 2,
                "episodeCount": 8
            }
        ]
    },
    {
        "type": "tv",
        "title": "Silo",
        "year": 2024,
        "tvdbId": 407008,
        "overview": "Men and women live in a giant underground silo with several regulations which they believe are in place to protect them from the toxic and ruined world on the surface.",
        "rating": 8.4,
        "runtime": "Series",
        "certification": "TV-MA",
        "genres": [
            "Drama",
            "Sci-Fi"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/oyzAYNByiHhaWe4Hfi1PUs7qrEL.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/4XccmjsOmQZw8S2iW1wvlvmb5v1.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 10
            },
            {
                "seasonNumber": 2,
                "episodeCount": 10
            }
        ]
    },
    {
        "type": "tv",
        "title": "Arcane",
        "year": 2024,
        "tvdbId": 370163,
        "overview": "Amid the stark discord of twin cities Piltover and Zaun, two sisters fight on rival sides of a war between magic technologies and incompatible convictions.",
        "rating": 9.1,
        "runtime": "Series",
        "certification": "TV-14",
        "genres": [
            "Animation",
            "Sci-Fi",
            "Action"
        ],
        "remotePoster": "https://image.tmdb.org/t/p/w500/87C0wecB8bHb1PsrKBI4kJiEkgV.jpg",
        "backdrop": "https://image.tmdb.org/t/p/original/5cvnxEHT3e39DvT6ARw4GNCFrB0.jpg",
        "seasons": [
            {
                "seasonNumber": 1,
                "episodeCount": 9
            },
            {
                "seasonNumber": 2,
                "episodeCount": 9
            }
        ]
    }
];

function extractImage(images, type) {
    if (!Array.isArray(images)) return null;
    const match = images.find(img => (img.coverType || '').toLowerCase() === type.toLowerCase());
    return match ? (match.remoteUrl || match.url) : null;
}

// ─────────────────────────────────────────────────────────────────────────────
// EMBEDDED FRONTEND (OFFICIAL OVERSEERR / JELLYSEERR RESPONSIVE UI)
// ─────────────────────────────────────────────────────────────────────────────

// BASE64 EMBEDDED FALLBACK FRONTEND
const EMBEDDED_HTML_B64 = "PCFET0NUWVBFIGh0bWw+CjxodG1sIGxhbmc9ImVuIiBjbGFzcz0iZGFyayI+CjxoZWFkPgo8bWV0YSBjaGFyc2V0PSJVVEYtOCIgLz4KPG1ldGEgbmFtZT0idmlld3BvcnQiIGNvbnRlbnQ9IndpZHRoPWRldmljZS13aWR0aCwgaW5pdGlhbC1zY2FsZT0xLjAsIG1heGltdW0tc2NhbGU9NS4wLCB1c2VyLXNjYWxhYmxlPXllcyIgLz4KPG1ldGEgbmFtZT0icmVmZXJyZXIiIGNvbnRlbnQ9Im5vLXJlZmVycmVyIiAvPgo8dGl0bGU+SmVsbHlzZWVycjwvdGl0bGU+CjxzY3JpcHQgc3JjPSJodHRwczovL2Nkbi50YWlsd2luZGNzcy5jb20iPjwvc2NyaXB0Pgo8c2NyaXB0PgogIGlmICh3aW5kb3cudGFpbHdpbmQpIHsKICAgIHRhaWx3aW5kLmNvbmZpZyA9IHsKICAgICAgZGFya01vZGU6ICdjbGFzcycsCiAgICAgIHRoZW1lOiB7CiAgICAgICAgZXh0ZW5kOiB7CiAgICAgICAgICBjb2xvcnM6IHsKICAgICAgICAgICAgYnJhbmQ6IHsgNTAwOiAnIzYzNjZmMScsIDYwMDogJyM0ZjQ2ZTUnLCA3MDA6ICcjNDMzOGNhJyB9CiAgICAgICAgICB9LAogICAgICAgICAgZm9udEZhbWlseTogeyBzYW5zOiBbJ0ludGVyJywgJ3VpLXNhbnMtc2VyaWYnLCAnc3lzdGVtLXVpJywgJ3NhbnMtc2VyaWYnXSB9CiAgICAgICAgfQogICAgICB9CiAgICB9OwogIH0KPC9zY3JpcHQ+CjxsaW5rIGhyZWY9Imh0dHBzOi8vZm9udHMuZ29vZ2xlYXBpcy5jb20vY3NzMj9mYW1pbHk9SW50ZXI6d2dodEA0MDA7NTAwOzYwMDs3MDA7ODAwJmRpc3BsYXk9c3dhcCIgcmVsPSJzdHlsZXNoZWV0Ij4KPHN0eWxlPgogIDpyb290IHsKICAgIC0tYmctYmFzZTogIzA2MDkxMzsKICAgIC0tYmctc3VyZmFjZTogIzBjMTIyMjsKICAgIC0tYmctY2FyZDogIzExMTgyNzsKICAgIC0tYm9yZGVyLXN1YnRsZTogcmdiYSgyNTUsIDI1NSwgMjU1LCAwLjA4KTsKICAgIC0tYWNjZW50OiAjNjM2NmYxOwogICAgLS10ZXh0LXByaW1hcnk6ICNmOGZhZmM7CiAgICAtLXRleHQtbXV0ZWQ6ICM5NGEzYjg7CiAgfQogIGh0bWwsIGJvZHkgewogICAgb3ZlcnNjcm9sbC1iZWhhdmlvcjogbm9uZTsKICAgIC13ZWJraXQtZm9udC1zbW9vdGhpbmc6IGFudGlhbGlhc2VkOwogICAgYmFja2dyb3VuZC1jb2xvcjogdmFyKC0tYmctYmFzZSk7CiAgICBjb2xvcjogdmFyKC0tdGV4dC1wcmltYXJ5KTsKICAgIGZvbnQtZmFtaWx5OiBJbnRlciwgc3lzdGVtLXVpLCAtYXBwbGUtc3lzdGVtLCBzYW5zLXNlcmlmOwogICAgbWFyZ2luOiAwOwogICAgcGFkZGluZzogMDsKICAgIGhlaWdodDogMTAwJTsKICAgIG1pbi1oZWlnaHQ6IDEwMCU7CiAgfQogICogeyBib3gtc2l6aW5nOiBib3JkZXItYm94OyAtd2Via2l0LXRhcC1oaWdobGlnaHQtY29sb3I6IHRyYW5zcGFyZW50OyB9CiAgLmdsYXNzIHsKICAgIGJhY2tncm91bmQ6IHJnYmEoMTIsIDE4LCAzNCwgMC44NSk7CiAgICBiYWNrZHJvcC1maWx0ZXI6IGJsdXIoMTZweCk7CiAgICAtd2Via2l0LWJhY2tkcm9wLWZpbHRlcjogYmx1cigxNnB4KTsKICAgIGJvcmRlci1ib3R0b206IDFweCBzb2xpZCB2YXIoLS1ib3JkZXItc3VidGxlKTsKICB9CiAgLmdsYXNzLWNhcmQgewogICAgYmFja2dyb3VuZDogcmdiYSgxNywgMjQsIDM5LCAwLjg1KTsKICAgIGJhY2tkcm9wLWZpbHRlcjogYmx1cigxNnB4KTsKICAgIC13ZWJraXQtYmFja2Ryb3AtZmlsdGVyOiBibHVyKDE2cHgpOwogICAgYm9yZGVyOiAxcHggc29saWQgcmdiYSgyNTUsIDI1NSwgMjU1LCAwLjA3KTsKICB9CiAgLnBvc3RlciB7CiAgICBwb3NpdGlvbjogcmVsYXRpdmU7CiAgICBvdmVyZmxvdzogaGlkZGVuOwogICAgYmFja2dyb3VuZDogIzBmMTcyYTsKICB9CiAgLnBvc3RlciBpbWcgewogICAgd2lkdGg6IDEwMCU7CiAgICBoZWlnaHQ6IDEwMCU7CiAgICBvYmplY3QtZml0OiBjb3ZlcjsKICAgIHRyYW5zaXRpb246IHRyYW5zZm9ybSAuMjVzIGVhc2U7CiAgfQogIC5wb3N0ZXI6aG92ZXIgaW1nIHsKICAgIHRyYW5zZm9ybTogc2NhbGUoMS4wNSk7CiAgfQogIC5wb3N0ZXI6OmFmdGVyIHsKICAgIGNvbnRlbnQ6ICcnOwogICAgcG9zaXRpb246IGFic29sdXRlOwogICAgaW5zZXQ6IDA7CiAgICBiYWNrZ3JvdW5kOiBsaW5lYXItZ3JhZGllbnQodG8gdG9wLCByZ2JhKDYsIDksIDE5LCAwLjk1KSAwJSwgcmdiYSg2LCA5LCAxOSwgMC4xNSkgNDUlLCB0cmFuc3BhcmVudCA3MCUpOwogIH0KICAuYmFkZ2UgewogICAgYmFja2dyb3VuZDogcmdiYSgwLCAwLCAwLCAwLjgpOwogICAgYm9yZGVyOiAxcHggc29saWQgcmdiYSgyNTUsIDI1NSwgMjU1LCAwLjE1KTsKICAgIGJhY2tkcm9wLWZpbHRlcjogYmx1cig0cHgpOwogIH0KICAuYmFkZ2UtYXZhaWxhYmxlIHsKICAgIGJhY2tncm91bmQ6IHJnYmEoMTYsIDE4NSwgMTI5LCAwLjI1KTsKICAgIGJvcmRlcjogMXB4IHNvbGlkIHJnYmEoMTYsIDE4NSwgMTI5LCAwLjUpOwogICAgY29sb3I6ICM2ZWU3Yjc7CiAgfQogIC5iYWRnZS1wcm9jZXNzaW5nIHsKICAgIGJhY2tncm91bmQ6IHJnYmEoMjQ1LCAxNTgsIDExLCAwLjI1KTsKICAgIGJvcmRlcjogMXB4IHNvbGlkIHJnYmEoMjQ1LCAxNTgsIDExLCAwLjUpOwogICAgY29sb3I6ICNmZGU2OGE7CiAgfQogIC5iYWRnZS1yZXF1ZXN0ZWQgewogICAgYmFja2dyb3VuZDogcmdiYSg5OSwgMTAyLCAyNDEsIDAuMjUpOwogICAgYm9yZGVyOiAxcHggc29saWQgcmdiYSg5OSwgMTAyLCAyNDEsIDAuNSk7CiAgICBjb2xvcjogI2M3ZDJmZTsKICB9CiAgLnNhZmUtdG9wIHsgcGFkZGluZy10b3A6IGVudihzYWZlLWFyZWEtaW5zZXQtdG9wKTsgfQogIC5zYWZlLWJvdHRvbSB7IHBhZGRpbmctYm90dG9tOiBlbnYoc2FmZS1hcmVhLWluc2V0LWJvdHRvbSk7IH0KICAuc2Vhc29uLWNoaXAgewogICAgcGFkZGluZzogNXB4IDEwcHg7CiAgICBib3JkZXItcmFkaXVzOiA4cHg7CiAgICBmb250LXNpemU6IDExcHg7CiAgICBmb250LXdlaWdodDogNTAwOwogICAgY3Vyc29yOiBwb2ludGVyOwogICAgYm9yZGVyOiAxcHggc29saWQgcmdiYSgyNTUsIDI1NSwgMjU1LCAwLjEpOwogICAgYmFja2dyb3VuZDogcmdiYSgzMCwgNDEsIDU5LCAwLjYpOwogICAgY29sb3I6ICNjYmQ1ZTE7CiAgfQogIC5zZWFzb24tY2hpcC5zZWxlY3RlZCB7CiAgICBiYWNrZ3JvdW5kOiAjNGY0NmU1OwogICAgYm9yZGVyLWNvbG9yOiAjNjM2NmYxOwogICAgY29sb3I6ICNmZmZmZmY7CiAgfQogIC5kbC10cmFjayB7CiAgICB3aWR0aDogMTAwJTsKICAgIGhlaWdodDogNXB4OwogICAgYmFja2dyb3VuZDogcmdiYSgyNTUsIDI1NSwgMjU1LCAwLjA4KTsKICAgIGJvcmRlci1yYWRpdXM6IDk5OXB4OwogICAgb3ZlcmZsb3c6IGhpZGRlbjsKICB9CiAgLmRsLWZpbGwgewogICAgaGVpZ2h0OiAxMDAlOwogICAgYmFja2dyb3VuZDogbGluZWFyLWdyYWRpZW50KDkwZGVnLCAjNjM2NmYxLCAjMDBBNERDKTsKICAgIHRyYW5zaXRpb246IHdpZHRoIDAuM3MgZWFzZTsKICB9CiAgLmRsLWZpbGwucGF1c2VkIHsKICAgIGJhY2tncm91bmQ6ICNmNTllMGI7CiAgfQogIEBrZXlmcmFtZXMgdG9hc3RJbiB7CiAgICBmcm9tIHsgb3BhY2l0eTogMDsgdHJhbnNmb3JtOiB0cmFuc2xhdGVZKDEycHgpOyB9CiAgICB0byB7IG9wYWNpdHk6IDE7IHRyYW5zZm9ybTogdHJhbnNsYXRlWSgwKTsgfQogIH0KICAuYW5pbWF0ZS10b2FzdCB7IGFuaW1hdGlvbjogdG9hc3RJbiAwLjI1cyBjdWJpYy1iZXppZXIoMC4xNiwgMSwgMC4zLCAxKSBmb3J3YXJkczsgfQogIC5uYXYtcGlsbC5hY3RpdmUgewogICAgY29sb3I6ICNmZmZmZmY7CiAgICBiYWNrZ3JvdW5kOiByZ2JhKDk5LCAxMDIsIDI0MSwgMC4yNSk7CiAgICBib3JkZXItY29sb3I6IHJnYmEoOTksIDEwMiwgMjQxLCAwLjUpOwogIH0KICAuYm5hdi1jb250YWluZXIgewogICAgZGlzcGxheTogZmxleDsKICAgIHBvc2l0aW9uOiBmaXhlZDsKICAgIGJvdHRvbTogMDsKICAgIGxlZnQ6IDA7CiAgICByaWdodDogMDsKICAgIHdpZHRoOiAxMDAlOwogICAgYWxpZ24taXRlbXM6IHN0cmV0Y2g7CiAgICBqdXN0aWZ5LWNvbnRlbnQ6IHNwYWNlLWJldHdlZW47CiAgICBwYWRkaW5nOiAwOwogICAgbWFyZ2luOiAwOwogICAgaGVpZ2h0OiA1NnB4OwogICAgYm94LXNpemluZzogYm9yZGVyLWJveDsKICAgIHotaW5kZXg6IDUwOwogICAgYmFja2dyb3VuZDogcmdiYSgxMiwgMTgsIDM0LCAwLjk4KTsKICAgIGJhY2tkcm9wLWZpbHRlcjogYmx1cigxNnB4KTsKICAgIC13ZWJraXQtYmFja2Ryb3AtZmlsdGVyOiBibHVyKDE2cHgpOwogICAgYm9yZGVyLXRvcDogMXB4IHNvbGlkIHJnYmEoMjU1LCAyNTUsIDI1NSwgMC4xKTsKICB9CiAgLmJuYXYtaXRlbSB7CiAgICBmbGV4OiAxIDEgMCU7CiAgICBtaW4td2lkdGg6IDA7CiAgICBkaXNwbGF5OiBmbGV4OwogICAgZmxleC1kaXJlY3Rpb246IGNvbHVtbjsKICAgIGFsaWduLWl0ZW1zOiBjZW50ZXI7CiAgICBqdXN0aWZ5LWNvbnRlbnQ6IGNlbnRlcjsKICAgIHRleHQtYWxpZ246IGNlbnRlcjsKICAgIHBhZGRpbmc6IDZweCAycHg7CiAgICBiYWNrZ3JvdW5kOiB0cmFuc3BhcmVudDsKICAgIGJvcmRlcjogbm9uZTsKICAgIGNvbG9yOiAjOTRhM2I4OwogICAgY3Vyc29yOiBwb2ludGVyOwogICAgdHJhbnNpdGlvbjogY29sb3IgMC4xNXMgZWFzZSwgYmFja2dyb3VuZC1jb2xvciAwLjE1cyBlYXNlLCB0cmFuc2Zvcm0gMC4xcyBlYXNlOwogICAgLXdlYmtpdC10YXAtaGlnaGxpZ2h0LWNvbG9yOiB0cmFuc3BhcmVudDsKICAgIHVzZXItc2VsZWN0OiBub25lOwogIH0KICAuYm5hdi1pdGVtIHN2ZyB7CiAgICB3aWR0aDogMTlweDsKICAgIGhlaWdodDogMTlweDsKICAgIGZsZXgtc2hyaW5rOiAwOwogICAgbWFyZ2luLWJvdHRvbTogMnB4OwogIH0KICAuYm5hdi1pdGVtIHNwYW4gewogICAgZm9udC1zaXplOiAxMHB4OwogICAgZm9udC13ZWlnaHQ6IDUwMDsKICAgIGxldHRlci1zcGFjaW5nOiAtMC4wMWVtOwogICAgd2hpdGUtc3BhY2U6IG5vd3JhcDsKICAgIG92ZXJmbG93OiBoaWRkZW47CiAgICB0ZXh0LW92ZXJmbG93OiBlbGxpcHNpczsKICAgIG1heC13aWR0aDogMTAwJTsKICAgIGRpc3BsYXk6IGJsb2NrOwogICAgbGluZS1oZWlnaHQ6IDEuMTsKICB9CiAgLmJuYXYtaXRlbS5hY3RpdmUgewogICAgY29sb3I6ICM4MThjZjg7CiAgICBiYWNrZ3JvdW5kOiByZ2JhKDk5LCAxMDIsIDI0MSwgMC4xMik7CiAgfQogIC5ibmF2LWl0ZW06YWN0aXZlIHsKICAgIHRyYW5zZm9ybTogc2NhbGUoMC45NSk7CiAgfQo8L3N0eWxlPgo8L2hlYWQ+Cjxib2R5IGNsYXNzPSJtaW4taC1zY3JlZW4gZmxleCBmbGV4LWNvbCBzZWxlY3Rpb246YmctYnJhbmQtNTAwIHNlbGVjdGlvbjp0ZXh0LXdoaXRlIHBiLTI0IGxnOnBiLTYiPgoKPCEtLSBNaW5pbWFsIEhlYWRlciAtLT4KPGhlYWRlciBjbGFzcz0ic3RpY2t5IHRvcC0wIHotNDAgZ2xhc3Mgc2FmZS10b3AiPgogIDxkaXYgY2xhc3M9Im1heC13LVsxNzAwcHhdIG14LWF1dG8gcHgtMyBzbTpweC02IGgtMTQgc206aC0xNiBmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4gZ2FwLTMgc206Z2FwLTYiPgogICAgPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTIuNSBmbGV4LXNocmluay0wIGN1cnNvci1wb2ludGVyIiBvbmNsaWNrPSJzd2l0Y2hUYWIoJ2Rpc2NvdmVyJykiPgogICAgICA8ZGl2IGNsYXNzPSJ3LTggaC04IHJvdW5kZWQteGwgYmctYnJhbmQtNjAwIGZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktY2VudGVyIGZvbnQtYm9sZCB0ZXh0LXNtIHRleHQtd2hpdGUgc2hhZG93LW1kIHNoYWRvdy1icmFuZC02MDAvMzAiPko8L2Rpdj4KICAgICAgPHNwYW4gY2xhc3M9ImZvbnQtYm9sZCB0ZXh0LWJhc2UgdHJhY2tpbmctdGlnaHQgdGV4dC13aGl0ZSBoaWRkZW4geHM6aW5saW5lIj5KZWxseXNlZXJyPC9zcGFuPgogICAgPC9kaXY+CgogICAgPCEtLSBTZWFyY2ggQm94IC0tPgogICAgPGRpdiBjbGFzcz0iZmxleC0xIG1heC13LW1kIHJlbGF0aXZlIj4KICAgICAgPGlucHV0IGlkPSJzZWFyY2gtaW5wdXQiIHR5cGU9InRleHQiIHBsYWNlaG9sZGVyPSJTZWFyY2ggMjAyNC0yMDI2IG1lZGlhLi4uIiBhdXRvY29tcGxldGU9Im9mZiIKICAgICAgICBjbGFzcz0idy1mdWxsIGJnLWdyYXktOTAwLzkwIGJvcmRlciBib3JkZXItZ3JheS04MDAgcm91bmRlZC14bCBweC0zIHB5LTEuNSBwbC04IHByLTcgdGV4dC14cyBzbTp0ZXh0LXNtIHRleHQtZ3JheS0xMDAgcGxhY2Vob2xkZXItZ3JheS01MDAgZm9jdXM6b3V0bGluZS1ub25lIGZvY3VzOnJpbmctMSBmb2N1czpyaW5nLWJyYW5kLTUwMC83MCIgLz4KICAgICAgPHN2ZyBjbGFzcz0idy0zLjUgaC0zLjUgYWJzb2x1dGUgbGVmdC0yLjUgdG9wLTIuNSB0ZXh0LWdyYXktNDAwIHBvaW50ZXItZXZlbnRzLW5vbmUiIGZpbGw9Im5vbmUiIHN0cm9rZT0iY3VycmVudENvbG9yIiB2aWV3Qm94PSIwIDAgMjQgMjQiPgogICAgICAgIDxwYXRoIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlLXdpZHRoPSIyIiBkPSJNMjEgMjFsLTQuMzUtNC4zNU0xNyAxMWE2IDYgMCAxMS0xMiAwIDYgNiAwIDAxMTIgMHoiLz4KICAgICAgPC9zdmc+CiAgICAgIDxidXR0b24gaWQ9InNlYXJjaC1jbGVhciIgY2xhc3M9ImhpZGRlbiBhYnNvbHV0ZSByaWdodC0yLjUgdG9wLTIuNSB0ZXh0LWdyYXktNDAwIGhvdmVyOnRleHQtd2hpdGUiPgogICAgICAgIDxzdmcgY2xhc3M9InctMy41IGgtMy41IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgdmlld0JveD0iMCAwIDI0IDI0Ij48cGF0aCBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZS13aWR0aD0iMiIgZD0iTTYgMThMMTggNk02IDZsMTIgMTIiLz48L3N2Zz4KICAgICAgPC9idXR0b24+CiAgICA8L2Rpdj4KCiAgICA8IS0tIERlc2t0b3AgTmF2IFBpbGxzIC0tPgogICAgPG5hdiBjbGFzcz0iaGlkZGVuIGxnOmZsZXggaXRlbXMtY2VudGVyIGdhcC0xLjUgdGV4dC14cyBmb250LXNlbWlib2xkIj4KICAgICAgPGJ1dHRvbiBvbmNsaWNrPSJzd2l0Y2hUYWIoJ2Rpc2NvdmVyJykiIGNsYXNzPSJuYXYtcGlsbCBhY3RpdmUgcHgtMyBweS0xLjUgcm91bmRlZC1sZyB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUgYm9yZGVyIGJvcmRlci10cmFuc3BhcmVudCB0cmFuc2l0aW9uLWNvbG9ycyIgZGF0YS10YWI9ImRpc2NvdmVyIj5EaXNjb3ZlcjwvYnV0dG9uPgogICAgICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYignbW92aWVzJykiIGNsYXNzPSJuYXYtcGlsbCBweC0zIHB5LTEuNSByb3VuZGVkLWxnIHRleHQtZ3JheS0zMDAgaG92ZXI6dGV4dC13aGl0ZSBib3JkZXIgYm9yZGVyLXRyYW5zcGFyZW50IHRyYW5zaXRpb24tY29sb3JzIiBkYXRhLXRhYj0ibW92aWVzIj5Nb3ZpZXM8L2J1dHRvbj4KICAgICAgPGJ1dHRvbiBvbmNsaWNrPSJzd2l0Y2hUYWIoJ3R2JykiIGNsYXNzPSJuYXYtcGlsbCBweC0zIHB5LTEuNSByb3VuZGVkLWxnIHRleHQtZ3JheS0zMDAgaG92ZXI6dGV4dC13aGl0ZSBib3JkZXIgYm9yZGVyLXRyYW5zcGFyZW50IHRyYW5zaXRpb24tY29sb3JzIiBkYXRhLXRhYj0idHYiPlRWIFNob3dzPC9idXR0b24+CiAgICAgIDxidXR0b24gb25jbGljaz0ic3dpdGNoVGFiKCdyZXF1ZXN0cycpIiBjbGFzcz0ibmF2LXBpbGwgcHgtMyBweS0xLjUgcm91bmRlZC1sZyB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUgYm9yZGVyIGJvcmRlci10cmFuc3BhcmVudCB0cmFuc2l0aW9uLWNvbG9ycyBmbGV4IGl0ZW1zLWNlbnRlciBnYXAtMS41IiBkYXRhLXRhYj0icmVxdWVzdHMiPgogICAgICAgIFJlcXVlc3RzIDxzcGFuIGlkPSJyZXEtbmF2LWJhZGdlIiBjbGFzcz0icHgtMS41IHB5LTAuMiByb3VuZGVkLW1kIGJnLWJyYW5kLTYwMCB0ZXh0LXdoaXRlIGZvbnQtbW9ubyB0ZXh0LVsxMHB4XSI+MDwvc3Bhbj4KICAgICAgPC9idXR0b24+CiAgICAgIDxidXR0b24gb25jbGljaz0ic3dpdGNoVGFiKCdzZXJ2aWNlcycpIiBjbGFzcz0ibmF2LXBpbGwgcHgtMyBweS0xLjUgcm91bmRlZC1sZyB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUgYm9yZGVyIGJvcmRlci10cmFuc3BhcmVudCB0cmFuc2l0aW9uLWNvbG9ycyBmbGV4IGl0ZW1zLWNlbnRlciBnYXAtMS41IiBkYXRhLXRhYj0ic2VydmljZXMiPgogICAgICAgIFNlcnZpY2VzIDxzcGFuIGlkPSJzZXJ2aWNlcy1uYXYtYmFkZ2UiIGNsYXNzPSJ3LTIgaC0yIHJvdW5kZWQtZnVsbCBiZy1lbWVyYWxkLTQwMCI+PC9zcGFuPgogICAgICA8L2J1dHRvbj4KICAgICAgPGJ1dHRvbiBvbmNsaWNrPSJzd2l0Y2hUYWIoJ2Rvd25sb2FkcycpIiBjbGFzcz0ibmF2LXBpbGwgcHgtMyBweS0xLjUgcm91bmRlZC1sZyB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUgYm9yZGVyIGJvcmRlci10cmFuc3BhcmVudCB0cmFuc2l0aW9uLWNvbG9ycyBmbGV4IGl0ZW1zLWNlbnRlciBnYXAtMS41IiBkYXRhLXRhYj0iZG93bmxvYWRzIj4KICAgICAgICBEb3dubG9hZHMgPHNwYW4gaWQ9ImRsLWNvdW50IiBjbGFzcz0icHgtMS41IHB5LTAuMiByb3VuZGVkLW1kIGJnLWdyYXktODAwIHRleHQtZ3JheS0zMDAgZm9udC1tb25vIHRleHQtWzEwcHhdIj4wPC9zcGFuPgogICAgICA8L2J1dHRvbj4KICAgIDwvbmF2PgoKICAgIDwhLS0gU2V0dGluZ3MgQnV0dG9uIC0tPgogICAgPGJ1dHRvbiBpZD0iYnRuLW9wZW4tc2V0dGluZ3MiIHRpdGxlPSJDb25uZWN0aW9uIFNldHRpbmdzIiBjbGFzcz0icC0xLjUgcm91bmRlZC14bCBib3JkZXIgYm9yZGVyLWdyYXktODAwIHRleHQtZ3JheS0zMDAgaG92ZXI6dGV4dC13aGl0ZSB0cmFuc2l0aW9uLWNvbG9ycyB0ZXh0LXhzIGZvbnQtbW9ubyBmbGV4IGl0ZW1zLWNlbnRlciBnYXAtMS41Ij4KICAgICAgPHN2ZyBjbGFzcz0idy00IGgtNCB0ZXh0LWJyYW5kLTQwMCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJjdXJyZW50Q29sb3IiIHZpZXdCb3g9IjAgMCAyNCAyNCI+CiAgICAgICAgPHBhdGggc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2Utd2lkdGg9IjIiIGQ9Ik0xMC4zMjUgNC4zMTdjLjQyNi0xLjc1NiAyLjkyNC0xLjc1NiAzLjM1IDBhMS43MjQgMS43MjQgMCAwMDIuNTczIDEuMDY2YzEuNTQzLS45NCAzLjMxLjgyNiAyLjM3IDIuMzdhMS43MjQgMS43MjQgMCAwMDEuMDY1IDIuNTcyYzEuNzU2LjQyNiAxLjc1NiAyLjkyNCAwIDMuMzVhMS43MjQgMS43MjQgMCAwMC0xLjA2NiAyLjU3M2MuOTQgMS41NDMtLjgyNiAzLjMxLTIuMzcgMi4zN2ExLjcyNCAxLjcyNCAwIDAwLTIuNTcyIDEuMDY1Yy0uNDI2IDEuNzU2LTIuOTI0IDEuNzU2LTMuMzUgMGExLjcyNCAxLjcyNCAwIDAwLTIuNTczLTEuMDY2Yy0xLjU0My45NC0zLjMxLS44MjYtMi4zNy0yLjM3YTEuNzI0IDEuNzI0IDAgMDAtMS4wNjUtMi41NzJjLTEuNzU2LS40MjYtMS43NTYtMi45MjQgMC0zLjM1YTEuNzI0IDEuNzI0IDAgMDAxLjA2Ni0yLjU3M2MtLjk0LTEuNTQzLjgyNi0zLjMxIDIuMzctMi4zNy45OTYuNjA4IDIuMjk2LjA3IDIuNTcyLTEuMDY1eiIvPgogICAgICAgIDxwYXRoIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlLXdpZHRoPSIyIiBkPSJNMTUgMTJhMyAzIDAgMTEtNiAwIDMgMyAwIDAxNiAweiIvPgogICAgICA8L3N2Zz4KICAgICAgPHNwYW4gaWQ9Imhvc3QtYmFkZ2UiIGNsYXNzPSJoaWRkZW4gc206aW5saW5lIHRleHQtZ3JheS00MDAiPjo1MDU1PC9zcGFuPgogICAgPC9idXR0b24+CiAgPC9kaXY+CjwvaGVhZGVyPgoKPCEtLSBNYWluIENvbnRhaW5lciAtLT4KPG1haW4gY2xhc3M9Im1heC13LVsxNzAwcHhdIG14LWF1dG8gcHgtMyBzbTpweC02IHB5LTQgc3BhY2UteS02IGZsZXgtMSBtaW4taC1bY2FsYygxMDB2aC0xNDBweCldIj4KCiAgPCEtLSBTZWFyY2ggUmVzdWx0cyBPdmVybGF5IEdyaWQgLS0+CiAgPHNlY3Rpb24gaWQ9InNlYXJjaC1zZWN0aW9uIiBjbGFzcz0iaGlkZGVuIGdsYXNzLWNhcmQgcm91bmRlZC0yeGwgcC00IHNwYWNlLXktMyI+CiAgICA8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4iPgogICAgICA8aDMgY2xhc3M9InRleHQtc20gZm9udC1ib2xkIGZsZXggaXRlbXMtY2VudGVyIGdhcC0yIj4KICAgICAgICA8c3Bhbj5TZWFyY2ggUmVzdWx0czwvc3Bhbj4KICAgICAgICA8c3BhbiBpZD0ic2VhcmNoLXF1ZXJ5LWJhZGdlIiBjbGFzcz0idGV4dC14cyBiZy1icmFuZC01MDAvMjAgdGV4dC1icmFuZC0zMDAgcHgtMiBweS0wLjIgcm91bmRlZCBmb250LW1vbm8iPjwvc3Bhbj4KICAgICAgPC9oMz4KICAgICAgPGJ1dHRvbiBpZD0ic2VhcmNoLWNsb3NlLWJ0biIgY2xhc3M9InRleHQteHMgdGV4dC1ncmF5LTQwMCBob3Zlcjp0ZXh0LXdoaXRlIHB4LTIgcHktMSByb3VuZGVkIGJvcmRlciBib3JkZXItZ3JheS04MDAiPkNsb3NlPC9idXR0b24+CiAgICA8L2Rpdj4KICAgIDwhLS0gQ2xlYW4gNC1Db2x1bW4gR3JpZCBvbiBNb2JpbGUsIGV4cGFuZGluZyBvbiBQQyAtLT4KICAgIDxkaXYgaWQ9InNlYXJjaC1ncmlkIiBjbGFzcz0iZ3JpZCBncmlkLWNvbHMtNCBzbTpncmlkLWNvbHMtNSBtZDpncmlkLWNvbHMtNiBsZzpncmlkLWNvbHMtNyB4bDpncmlkLWNvbHMtOCBnYXAtMiBzbTpnYXAtMyI+PC9kaXY+CiAgPC9zZWN0aW9uPgoKICA8IS0tIFRBQiAxOiBESVNDT1ZFUiAtLT4KICA8ZGl2IGlkPSJ2aWV3LWRpc2NvdmVyIiBjbGFzcz0ic3BhY2UteS02IG1pbi1oLVs2NXZoXSI+CgogICAgPCEtLSBNaW5pbWFsIEhlcm8gRmVhdHVyZSBCYW5uZXIgLS0+CiAgICA8c2VjdGlvbiBjbGFzcz0icmVsYXRpdmUgcm91bmRlZC0yeGwgb3ZlcmZsb3ctaGlkZGVuIGdsYXNzLWNhcmQgc2hhZG93LXhsIGJvcmRlciBib3JkZXItd2hpdGUvNSI+CiAgICAgIDxkaXYgaWQ9Imhlcm8tYmciIGNsYXNzPSJhYnNvbHV0ZSBpbnNldC0wIGJnLWNvdmVyIGJnLWNlbnRlciB0cmFuc2l0aW9uLWFsbCBkdXJhdGlvbi01MDAiPjwvZGl2PgogICAgICA8ZGl2IGNsYXNzPSJhYnNvbHV0ZSBpbnNldC0wIGJnLWdyYWRpZW50LXRvLXQgZnJvbS1ncmF5LTk1MCB2aWEtZ3JheS05NTAvODAgdG8tZ3JheS05NTAvNDAiPjwvZGl2PgogICAgICAKICAgICAgPGRpdiBjbGFzcz0icmVsYXRpdmUgei0xMCBwLTMuNSBzbTpwLTYgZmxleCBmbGV4LWNvbCBqdXN0aWZ5LWVuZCI+CiAgICAgICAgPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTMuNSBzbTpnYXAtNSI+CiAgICAgICAgICA8IS0tIFNwb3RsaWdodCBDb3ZlciBJbWFnZSAtLT4KICAgICAgICAgIDxkaXYgY2xhc3M9InctMjAgc206dy0yOCBhc3BlY3QtWzIvM10gcm91bmRlZC14bCBvdmVyZmxvdy1oaWRkZW4gc2hhZG93LTJ4bCBwb3N0ZXIgYm9yZGVyIGJvcmRlci13aGl0ZS8xMCBmbGV4LXNocmluay0wIGN1cnNvci1wb2ludGVyIiBvbmNsaWNrPSJvcGVuSGVyb01vZGFsKCkiPgogICAgICAgICAgICA8aW1nIGlkPSJoZXJvLXBvc3RlciIgc3JjPSIiIGFsdD0iQ292ZXIgUG9zdGVyIiBjbGFzcz0idy1mdWxsIGgtZnVsbCBvYmplY3QtY292ZXIgcG9pbnRlci1ldmVudHMtbm9uZSIgLz4KICAgICAgICAgIDwvZGl2PgoKICAgICAgICAgIDxkaXYgY2xhc3M9ImZsZXgtMSBtaW4tdy0wIj4KICAgICAgICAgICAgPGRpdiBjbGFzcz0iZmxleCBmbGV4LXdyYXAgaXRlbXMtY2VudGVyIGdhcC0xLjUgdGV4dC1bMTFweF0gbWItMS41Ij4KICAgICAgICAgICAgICA8c3BhbiBjbGFzcz0icHgtMiBweS0wLjIgcm91bmRlZCBiZy1icmFuZC02MDAvMzAgdGV4dC1icmFuZC0zMDAgZm9udC1zZW1pYm9sZCBmb250LW1vbm8gdGV4dC1bMTBweF0iPlNQT1RMSUdIVDwvc3Bhbj4KICAgICAgICAgICAgICA8c3BhbiBpZD0iaGVyby10eXBlLWJhZGdlIiBjbGFzcz0icHgtMS41IHB5LTAuMiByb3VuZGVkIGJnLWdyYXktODAwIHRleHQtZ3JheS0zMDAgdXBwZXJjYXNlIGZvbnQtbW9ubyB0ZXh0LVsxMHB4XSI+TU9WSUU8L3NwYW4+CiAgICAgICAgICAgICAgPHNwYW4gaWQ9Imhlcm8tbWV0YSIgY2xhc3M9InRleHQtZ3JheS00MDAiPjwvc3Bhbj4KICAgICAgICAgICAgICA8c3BhbiBpZD0iaGVyby1saWJyYXJ5LXN0YXR1cyIgY2xhc3M9ImhpZGRlbiB0ZXh0LVsxMHB4XSBweC0xLjUgcHktMC4yIHJvdW5kZWQgZm9udC1tZWRpdW0iPjwvc3Bhbj4KICAgICAgICAgICAgPC9kaXY+CiAgICAgICAgICAgIDxoMSBpZD0iaGVyby10aXRsZSIgY2xhc3M9InRleHQtYmFzZSBzbTp0ZXh0LTJ4bCBmb250LWJvbGQgdGV4dC13aGl0ZSBsZWFkaW5nLXRpZ2h0IHRydW5jYXRlIj5Mb2FkaW5nLi4uPC9oMT4KICAgICAgICAgICAgPHAgaWQ9Imhlcm8tb3ZlcnZpZXciIGNsYXNzPSJ0ZXh0LXhzIHRleHQtZ3JheS0zMDAgbXQtMSBsaW5lLWNsYW1wLTIgbGVhZGluZy1yZWxheGVkIGhpZGRlbiB4czpibG9jayI+PC9wPgogICAgICAgICAgICAKICAgICAgICAgICAgPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTIgbXQtMiB0ZXh0LXhzIj4KICAgICAgICAgICAgICA8c3BhbiBjbGFzcz0idGV4dC1hbWJlci00MDAgZm9udC1ib2xkIGZsZXggaXRlbXMtY2VudGVyIGdhcC0xIj4KICAgICAgICAgICAgICAgIDxzdmcgY2xhc3M9InctMy41IGgtMy41IiBmaWxsPSJjdXJyZW50Q29sb3IiIHZpZXdCb3g9IjAgMCAyMCAyMCI+PHBhdGggZD0iTTkuMDQ5IDIuOTI3Yy4zLS45MjEgMS42MDMtLjkyMSAxLjkwMiAwbDEuMDcgMy4yOTJhMSAxIDAgMDAuOTUuNjloMy40NjJjLjk2OSAwIDEuMzcxIDEuMjQuNTg4IDEuODFsLTIuOCAyLjAzNGExIDEgMCAwMC0uMzY0IDEuMTE4bDEuMDcgMy4yOTJjLjMuOTIxLS43NTUgMS42ODgtMS41NCAxLjExOGwtMi44LTIuMDM0YTEgMSAwIDAwLTEuMTc1IDBsLTIuOCAyLjAzNGMtLjc4NC41Ny0xLjgzOC0uMTk3LTEuNTM5LTEuMTE4bDEuMDctMy4yOTJhMSAxIDAgMDAtLjM2NC0xLjExOEwyLjk4IDguNzJjLS43ODMtLjU3LS4zOC0xLjgxLjU4OC0xLjgxaDMuNDYxYTEgMSAwIDAwLjk1MS0uNjlsMS4wNy0zLjI5MnoiLz48L3N2Zz4KICAgICAgICAgICAgICAgIDxzcGFuIGlkPSJoZXJvLXJhdGluZyI+MC4wPC9zcGFuPgogICAgICAgICAgICAgIDwvc3Bhbj4KICAgICAgICAgICAgICA8c3BhbiBjbGFzcz0idGV4dC1ncmF5LTYwMCI+fDwvc3Bhbj4KICAgICAgICAgICAgICA8c3BhbiBpZD0iaGVyby1nZW5yZXMiIGNsYXNzPSJ0ZXh0LWdyYXktNDAwIHRydW5jYXRlIj48L3NwYW4+CiAgICAgICAgICAgIDwvZGl2PgoKICAgICAgICAgICAgPCEtLSBEaXJlY3QgSW5zdGFudCBBY3Rpb24gQnV0dG9ucyAtLT4KICAgICAgICAgICAgPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTIgbXQtMyI+CiAgICAgICAgICAgICAgPGJ1dHRvbiBpZD0iaGVyby1hY3Rpb24tYnRuIiBjbGFzcz0icHgtNCBweS0xLjUgcm91bmRlZC1sZyBiZy1icmFuZC02MDAgaG92ZXI6YmctYnJhbmQtNTAwIHRleHQtd2hpdGUgZm9udC1zZW1pYm9sZCB0ZXh0LXhzIHRyYW5zaXRpb24tYWxsIGFjdGl2ZTpzY2FsZS05NSBzaGFkb3ciPgogICAgICAgICAgICAgICAgPHNwYW4gaWQ9Imhlcm8tYWN0aW9uLXRleHQiPlJlcXVlc3Q8L3NwYW4+CiAgICAgICAgICAgICAgPC9idXR0b24+CiAgICAgICAgICAgICAgPGJ1dHRvbiBvbmNsaWNrPSJvcGVuSGVyb01vZGFsKCkiIGNsYXNzPSJweC0zIHB5LTEuNSByb3VuZGVkLWxnIGJvcmRlciBib3JkZXItZ3JheS04MDAgdGV4dC1ncmF5LTMwMCBob3Zlcjp0ZXh0LXdoaXRlIHRleHQteHMgdHJhbnNpdGlvbi1hbGwiPgogICAgICAgICAgICAgICAgRGV0YWlscwogICAgICAgICAgICAgIDwvYnV0dG9uPgogICAgICAgICAgICA8L2Rpdj4KICAgICAgICAgIDwvZGl2PgogICAgICAgIDwvZGl2PgogICAgICA8L2Rpdj4KICAgIDwvc2VjdGlvbj4KCiAgICA8IS0tIFRyZW5kaW5nIFNlY3Rpb24gKEV4YWN0IDQgaW4gR3JpZCBvbiBNb2JpbGUsIE1heCBmaXQgb24gUEMpIC0tPgogICAgPHNlY3Rpb24gY2xhc3M9InNwYWNlLXktMi41Ij4KICAgICAgPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIganVzdGlmeS1iZXR3ZWVuIj4KICAgICAgICA8aDIgY2xhc3M9InRleHQtc20gc206dGV4dC1iYXNlIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIHRyYWNraW5nLXRpZ2h0Ij5UcmVuZGluZyAoMjAyNC0yMDI2KTwvaDI+CiAgICAgICAgPHNwYW4gY2xhc3M9InRleHQtWzExcHhdIHRleHQtZ3JheS01MDAiPlRhcCB0byByZXF1ZXN0PC9zcGFuPgogICAgICA8L2Rpdj4KICAgICAgPGRpdiBpZD0idHJlbmRpbmctZ3JpZCIgY2xhc3M9ImdyaWQgZ3JpZC1jb2xzLTQgc206Z3JpZC1jb2xzLTUgbWQ6Z3JpZC1jb2xzLTYgbGc6Z3JpZC1jb2xzLTcgeGw6Z3JpZC1jb2xzLTggZ2FwLTIgc206Z2FwLTMiPjwvZGl2PgogICAgPC9zZWN0aW9uPgoKICAgIDwhLS0gUG9wdWxhciBNb3ZpZXMgU2VjdGlvbiAoNCBpbiBHcmlkIG9uIE1vYmlsZSkgLS0+CiAgICA8c2VjdGlvbiBjbGFzcz0ic3BhY2UteS0yLjUiPgogICAgICA8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4iPgogICAgICAgIDxoMiBjbGFzcz0idGV4dC1zbSBzbTp0ZXh0LWJhc2UgZm9udC1ib2xkIHRleHQtd2hpdGUgdHJhY2tpbmctdGlnaHQiPlBvcHVsYXIgTW92aWVzPC9oMj4KICAgICAgICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYignbW92aWVzJykiIGNsYXNzPSJ0ZXh0LXhzIHRleHQtYnJhbmQtNDAwIGhvdmVyOnRleHQtYnJhbmQtMzAwIGZvbnQtbWVkaXVtIj5BbGwgTW92aWVzPC9idXR0b24+CiAgICAgIDwvZGl2PgogICAgICA8ZGl2IGlkPSJtb3ZpZXMtcHJldmlldy1ncmlkIiBjbGFzcz0iZ3JpZCBncmlkLWNvbHMtNCBzbTpncmlkLWNvbHMtNSBtZDpncmlkLWNvbHMtNiBsZzpncmlkLWNvbHMtNyB4bDpncmlkLWNvbHMtOCBnYXAtMiBzbTpnYXAtMyI+PC9kaXY+CiAgICA8L3NlY3Rpb24+CgogICAgPCEtLSBQb3B1bGFyIFRWIFNob3dzIFNlY3Rpb24gKDQgaW4gR3JpZCBvbiBNb2JpbGUpIC0tPgogICAgPHNlY3Rpb24gY2xhc3M9InNwYWNlLXktMi41Ij4KICAgICAgPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIganVzdGlmeS1iZXR3ZWVuIj4KICAgICAgICA8aDIgY2xhc3M9InRleHQtc20gc206dGV4dC1iYXNlIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIHRyYWNraW5nLXRpZ2h0Ij5Qb3B1bGFyIFRWIFNob3dzPC9oMj4KICAgICAgICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYigndHYnKSIgY2xhc3M9InRleHQteHMgdGV4dC1icmFuZC00MDAgaG92ZXI6dGV4dC1icmFuZC0zMDAgZm9udC1tZWRpdW0iPkFsbCBTZXJpZXM8L2J1dHRvbj4KICAgICAgPC9kaXY+CiAgICAgIDxkaXYgaWQ9InR2LXByZXZpZXctZ3JpZCIgY2xhc3M9ImdyaWQgZ3JpZC1jb2xzLTQgc206Z3JpZC1jb2xzLTUgbWQ6Z3JpZC1jb2xzLTYgbGc6Z3JpZC1jb2xzLTcgeGw6Z3JpZC1jb2xzLTggZ2FwLTIgc206Z2FwLTMiPjwvZGl2PgogICAgPC9zZWN0aW9uPgoKICA8L2Rpdj4KCiAgPCEtLSBUQUIgMjogRlVMTCBNT1ZJRVMgKEV4YWN0IDQgaW4gR3JpZCBvbiBNb2JpbGUsIE1heCBmaXQgb24gUEMpIC0tPgogIDxkaXYgaWQ9InZpZXctbW92aWVzIiBjbGFzcz0iaGlkZGVuIHNwYWNlLXktMyI+CiAgICA8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4iPgogICAgICA8aDIgY2xhc3M9InRleHQtYmFzZSBzbTp0ZXh0LWxnIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIj5BbGwgTW92aWVzPC9oMj4KICAgICAgPHNwYW4gaWQ9Im1vdmllcy1mdWxsLWNvdW50IiBjbGFzcz0idGV4dC14cyBmb250LW1vbm8gdGV4dC1ncmF5LTQwMCI+PC9zcGFuPgogICAgPC9kaXY+CiAgICA8ZGl2IGlkPSJtb3ZpZXMtZnVsbC1ncmlkIiBjbGFzcz0iZ3JpZCBncmlkLWNvbHMtNCBzbTpncmlkLWNvbHMtNSBtZDpncmlkLWNvbHMtNiBsZzpncmlkLWNvbHMtNyB4bDpncmlkLWNvbHMtOCBnYXAtMiBzbTpnYXAtMyI+PC9kaXY+CiAgPC9kaXY+CgogIDwhLS0gVEFCIDM6IEZVTEwgVFYgU0hPV1MgKEV4YWN0IDQgaW4gR3JpZCBvbiBNb2JpbGUsIE1heCBmaXQgb24gUEMpIC0tPgogIDxkaXYgaWQ9InZpZXctdHYiIGNsYXNzPSJoaWRkZW4gc3BhY2UteS0zIj4KICAgIDxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktYmV0d2VlbiI+CiAgICAgIDxoMiBjbGFzcz0idGV4dC1iYXNlIHNtOnRleHQtbGcgZm9udC1ib2xkIHRleHQtd2hpdGUiPkFsbCBUViBTZXJpZXM8L2gyPgogICAgICA8c3BhbiBpZD0idHYtZnVsbC1jb3VudCIgY2xhc3M9InRleHQteHMgZm9udC1tb25vIHRleHQtZ3JheS00MDAiPjwvc3Bhbj4KICAgIDwvZGl2PgogICAgPGRpdiBpZD0idHYtZnVsbC1ncmlkIiBjbGFzcz0iZ3JpZCBncmlkLWNvbHMtNCBzbTpncmlkLWNvbHMtNSBtZDpncmlkLWNvbHMtNiBsZzpncmlkLWNvbHMtNyB4bDpncmlkLWNvbHMtOCBnYXAtMiBzbTpnYXAtMyI+PC9kaXY+CiAgPC9kaXY+CgogIDwhLS0gVEFCIDQ6IFJFUVVFU1RTICYgQ0FOQ0VMTEFUSU9OIExFREdFUiAtLT4KICA8ZGl2IGlkPSJ2aWV3LXJlcXVlc3RzIiBjbGFzcz0iaGlkZGVuIHNwYWNlLXktMyI+CiAgICA8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4iPgogICAgICA8aDIgY2xhc3M9InRleHQtYmFzZSBzbTp0ZXh0LWxnIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIj5BY3RpdmUgUmVxdWVzdHM8L2gyPgogICAgICA8YnV0dG9uIG9uY2xpY2s9ImZldGNoUmVxdWVzdHNMaXN0KCkiIGNsYXNzPSJweC0yLjUgcHktMSByb3VuZGVkLWxnIGJvcmRlciBib3JkZXItZ3JheS04MDAgdGV4dC14cyB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUiPlJlZnJlc2g8L2J1dHRvbj4KICAgIDwvZGl2PgogICAgPGRpdiBpZD0icmVxdWVzdHMtbGVkZ2VyLWxpc3QiIGNsYXNzPSJzcGFjZS15LTIuNSI+CiAgICAgIDxkaXYgY2xhc3M9InRleHQtY2VudGVyIHB5LTEyIHRleHQtZ3JheS01MDAgdGV4dC14cyI+Tm8gYWN0aXZlIHJlcXVlc3RzPC9kaXY+CiAgICA8L2Rpdj4KICA8L2Rpdj4KCiAgPCEtLSBUQUIgNTogQ09NUEFOSU9OIFNFUlZJQ0VTIC0tPgogIDxkaXYgaWQ9InZpZXctc2VydmljZXMiIGNsYXNzPSJoaWRkZW4gc3BhY2UteS0zIj4KICAgIDxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktYmV0d2VlbiI+CiAgICAgIDxoMiBjbGFzcz0idGV4dC1iYXNlIHNtOnRleHQtbGcgZm9udC1ib2xkIHRleHQtd2hpdGUiPkF1dG9tYXRpb24gU3RhY2sgU2VydmljZXM8L2gyPgogICAgICA8YnV0dG9uIG9uY2xpY2s9ImZldGNoU2VydmljZXNMaXN0KCkiIGNsYXNzPSJweC0yLjUgcHktMSByb3VuZGVkLWxnIGJvcmRlciBib3JkZXItZ3JheS04MDAgdGV4dC14cyB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUiPlNjYW48L2J1dHRvbj4KICAgIDwvZGl2PgogICAgPGRpdiBpZD0ic2VydmljZXMtZ3JpZCIgY2xhc3M9ImdyaWQgZ3JpZC1jb2xzLTEgbWQ6Z3JpZC1jb2xzLTIgZ2FwLTIuNSI+PC9kaXY+CiAgPC9kaXY+CgogIDwhLS0gVEFCIDY6IEFDVElWRSBET1dOTE9BRFMgLS0+CiAgPGRpdiBpZD0idmlldy1kb3dubG9hZHMiIGNsYXNzPSJoaWRkZW4gc3BhY2UteS0zIj4KICAgIDxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktYmV0d2VlbiI+CiAgICAgIDxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGdhcC0yIj4KICAgICAgICA8aDIgY2xhc3M9InRleHQtYmFzZSBzbTp0ZXh0LWxnIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIj5UcmFuc21pc3Npb24gVHJhbnNmZXJzPC9oMj4KICAgICAgICA8c3BhbiBjbGFzcz0idGV4dC14cyBmb250LW1vbm8gdGV4dC1ncmF5LTQwMCI+PHNwYW4gaWQ9InRhYi1kbC1zcGVlZCIgY2xhc3M9ImZvbnQtYm9sZCB0ZXh0LXdoaXRlIj4wLjAwPC9zcGFuPiBNQi9zPC9zcGFuPgogICAgICA8L2Rpdj4KICAgICAgPGJ1dHRvbiBvbmNsaWNrPSJmZXRjaERvd25sb2FkcygpIiBjbGFzcz0icHgtMi41IHB5LTEgcm91bmRlZC1sZyBib3JkZXIgYm9yZGVyLWdyYXktODAwIHRleHQteHMgdGV4dC1ncmF5LTMwMCBob3Zlcjp0ZXh0LXdoaXRlIj5SZWZyZXNoPC9idXR0b24+CiAgICA8L2Rpdj4KICAgIDxkaXYgaWQ9ImRvd25sb2Fkcy1sZWRnZXItbGlzdCIgY2xhc3M9InNwYWNlLXktMi41Ij48L2Rpdj4KICA8L2Rpdj4KCjwvbWFpbj4KCjwhLS0gSU5TVEFOVCBBQ1RJT04gQk9UVE9NIFNIRUVUIC8gTU9EQUwgKDEtVGFwIFJlcXVlc3QgLyBDYW5jZWwgLSBOTyBTQ1JPTExJTkcgRE9XTiEpIC0tPgo8ZGl2IGlkPSJkZXRhaWxzLW1vZGFsIiBjbGFzcz0iaGlkZGVuIGZpeGVkIGluc2V0LTAgei01MCBmbGV4IGl0ZW1zLWVuZCBzbTppdGVtcy1jZW50ZXIganVzdGlmeS1jZW50ZXIgcC0wIHNtOnAtNCBiZy1ibGFjay84MCBiYWNrZHJvcC1ibHVyLXNtIj4KICA8ZGl2IGNsYXNzPSJnbGFzcy1jYXJkIHctZnVsbCBtYXgtdy1sZyBtYXgtaC1bOTB2aF0gc206cm91bmRlZC0yeGwgcm91bmRlZC10LTN4bCBvdmVyZmxvdy1oaWRkZW4gZmxleCBmbGV4LWNvbCBzaGFkb3ctMnhsIHJlbGF0aXZlIGJvcmRlciBib3JkZXItd2hpdGUvMTAiPgogICAgCiAgICA8IS0tIE1vZGFsIENsb3NlIC0tPgogICAgPGJ1dHRvbiBpZD0ibW9kYWwtY2xvc2UtYnRuIiBjbGFzcz0iYWJzb2x1dGUgdG9wLTMgcmlnaHQtMyB6LTMwIHAtMS41IHJvdW5kZWQtZnVsbCBiZy1ibGFjay82MCB0ZXh0LWdyYXktMzAwIGhvdmVyOnRleHQtd2hpdGUiPgogICAgICA8c3ZnIGNsYXNzPSJ3LTUgaC01IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgdmlld0JveD0iMCAwIDI0IDI0Ij48cGF0aCBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZS13aWR0aD0iMiIgZD0iTTYgMThMMTggNk02IDZsMTIgMTIiLz48L3N2Zz4KICAgIDwvYnV0dG9uPgoKICAgIDwhLS0gTW9kYWwgQmFja2Ryb3AgQXJ0IC0tPgogICAgPGRpdiBpZD0ibW9kYWwtYmFja2Ryb3AiIGNsYXNzPSJ3LWZ1bGwgaC0zNiBzbTpoLTQ0IGJnLWNvdmVyIGJnLWNlbnRlciByZWxhdGl2ZSBmbGV4LXNocmluay0wIj4KICAgICAgPGRpdiBjbGFzcz0iYWJzb2x1dGUgaW5zZXQtMCBiZy1ncmFkaWVudC10by10IGZyb20tZ3JheS05NTAgdmlhLWdyYXktOTUwLzYwIHRvLXRyYW5zcGFyZW50Ij48L2Rpdj4KICAgIDwvZGl2PgoKICAgIDwhLS0gTW9kYWwgQ29udGVudCAtLT4KICAgIDxkaXYgY2xhc3M9InAtNCBvdmVyZmxvdy15LWF1dG8gc3BhY2UteS0zLjUgLW10LTE0IHJlbGF0aXZlIHotMTAgZmxleC0xIj4KICAgICAgPGRpdiBjbGFzcz0iZmxleCBnYXAtMyI+CiAgICAgICAgPGRpdiBjbGFzcz0idy0yMCBzbTp3LTI0IGFzcGVjdC1bMi8zXSByb3VuZGVkLXhsIG92ZXJmbG93LWhpZGRlbiBzaGFkb3ctMnhsIGZsZXgtc2hyaW5rLTAgcG9zdGVyIGJvcmRlciBib3JkZXItd2hpdGUvMTAiPgogICAgICAgICAgPGltZyBpZD0ibW9kYWwtcG9zdGVyIiBzcmM9IiIgYWx0PSJQb3N0ZXIiIGNsYXNzPSJ3LWZ1bGwgaC1mdWxsIG9iamVjdC1jb3ZlciBwb2ludGVyLWV2ZW50cy1ub25lIiAvPgogICAgICAgIDwvZGl2PgogICAgICAgIDxkaXYgY2xhc3M9ImZsZXgtMSBtaW4tdy0wIHB0LTQiPgogICAgICAgICAgPGRpdiBjbGFzcz0iZmxleCBmbGV4LXdyYXAgaXRlbXMtY2VudGVyIGdhcC0xLjUgbWItMSI+CiAgICAgICAgICAgIDxzcGFuIGlkPSJtb2RhbC10eXBlIiBjbGFzcz0icHgtMS41IHB5LTAuMiByb3VuZGVkIGJnLWJyYW5kLTUwMC8yMCB0ZXh0LWJyYW5kLTMwMCB0ZXh0LVsxMHB4XSBmb250LW1vbm8gdXBwZXJjYXNlIGZvbnQtYm9sZCI+PC9zcGFuPgogICAgICAgICAgICA8c3BhbiBpZD0ibW9kYWwteWVhciIgY2xhc3M9InRleHQteHMgdGV4dC1ncmF5LTQwMCBmb250LW1vbm8iPjwvc3Bhbj4KICAgICAgICAgICAgPHNwYW4gaWQ9Im1vZGFsLXN0YXR1cy1iYWRnZSIgY2xhc3M9ImhpZGRlbiB0ZXh0LVsxMHB4XSBweC0xLjUgcHktMC4yIHJvdW5kZWQgZm9udC1tZWRpdW0iPjwvc3Bhbj4KICAgICAgICAgIDwvZGl2PgogICAgICAgICAgPGgzIGlkPSJtb2RhbC10aXRsZSIgY2xhc3M9InRleHQtYmFzZSBzbTp0ZXh0LWxnIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIGxlYWRpbmctdGlnaHQgdHJ1bmNhdGUiPjwvaDM+CiAgICAgICAgICA8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBnYXAtMiB0ZXh0LXhzIHRleHQtZ3JheS00MDAgbXQtMC41Ij4KICAgICAgICAgICAgPHNwYW4gY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGdhcC0xIHRleHQtYW1iZXItNDAwIGZvbnQtYm9sZCI+CiAgICAgICAgICAgICAgPHN2ZyBjbGFzcz0idy0zLjUgaC0zLjUiIGZpbGw9ImN1cnJlbnRDb2xvciIgdmlld0JveD0iMCAwIDIwIDIwIj48cGF0aCBkPSJNOS4wNDkgMi45MjdjLjMtLjkyMSAxLjYwMy0uOTIxIDEuOTAyIDBsMS4wNyAzLjI5MmExIDEgMCAwMC45NS42OWgzLjQ2MmMuOTY5IDAgMS4zNzEgMS4yNC41ODggMS44MWwtMi44IDIuMDM0YTEgMSAwIDAwLS4zNjQgMS4xMThsMS4wNyAzLjI5MmMuMy45MjEtLjc1NSAxLjY4OC0xLjU0IDEuMTE4bC0yLjgtMi4wMzRhMSAxIDAgMDAtMS4xNzUgMGwtMi44IDIuMDM0Yy0uNzg0LjU3LTEuODM4LS4xOTctMS41MzktMS4xMThsMS4wNy0zLjI5MmExIDEgMCAwMC0uMzY0LTEuMTE4TDIuOTggOC43MmMtLjc4My0uNTctLjM4LTEuODEuNTg4LTEuODFoMy40NjFhMSAxIDAgMDAuOTUxLS42OWwxLjA3LTMuMjkyeiIvPjwvc3ZnPgogICAgICAgICAgICAgIDxzcGFuIGlkPSJtb2RhbC1yYXRpbmciPjAuMDwvc3Bhbj4KICAgICAgICAgICAgPC9zcGFuPgogICAgICAgICAgICA8c3Bhbj7igKI8L3NwYW4+CiAgICAgICAgICAgIDxzcGFuIGlkPSJtb2RhbC1ydW50aW1lIj48L3NwYW4+CiAgICAgICAgICA8L2Rpdj4KICAgICAgICA8L2Rpdj4KICAgICAgPC9kaXY+CgogICAgICA8ZGl2PgogICAgICAgIDxwIGlkPSJtb2RhbC1vdmVydmlldyIgY2xhc3M9InRleHQteHMgdGV4dC1ncmF5LTMwMCBsZWFkaW5nLXJlbGF4ZWQgbGluZS1jbGFtcC0zIj48L3A+CiAgICAgIDwvZGl2PgoKICAgICAgPCEtLSBRdWljayBSZXF1ZXN0IFBhcmFtZXRlcnMgLS0+CiAgICAgIDxkaXYgY2xhc3M9InJvdW5kZWQteGwgcC0yLjUgYmctZ3JheS05MDAvNjAgYm9yZGVyIGJvcmRlci1ncmF5LTgwMCBzcGFjZS15LTIgdGV4dC14cyI+CiAgICAgICAgPGRpdiBjbGFzcz0iZ3JpZCBncmlkLWNvbHMtMiBnYXAtMiI+CiAgICAgICAgICA8ZGl2PgogICAgICAgICAgICA8bGFiZWwgY2xhc3M9InRleHQtZ3JheS00MDAgYmxvY2sgbWItMSI+UXVhbGl0eTwvbGFiZWw+CiAgICAgICAgICAgIDxzZWxlY3QgaWQ9Im1vZGFsLXF1YWxpdHkiIGNsYXNzPSJ3LWZ1bGwgYmctZ3JheS05MDAgYm9yZGVyIGJvcmRlci1ncmF5LTgwMCByb3VuZGVkLWxnIHB4LTIgcHktMSB0ZXh0LXhzIHRleHQtZ3JheS0yMDAgZm9jdXM6b3V0bGluZS1ub25lIj4KICAgICAgICAgICAgICA8b3B0aW9uIHZhbHVlPSI0IiBzZWxlY3RlZD5IRC0xMDgwcDwvb3B0aW9uPgogICAgICAgICAgICAgIDxvcHRpb24gdmFsdWU9IjUiPlVsdHJhLUhEICg0Syk8L29wdGlvbj4KICAgICAgICAgICAgICA8b3B0aW9uIHZhbHVlPSIxIj5BbnkgUXVhbGl0eTwvb3B0aW9uPgogICAgICAgICAgICA8L3NlbGVjdD4KICAgICAgICAgIDwvZGl2PgogICAgICAgICAgPGRpdj4KICAgICAgICAgICAgPGxhYmVsIGNsYXNzPSJ0ZXh0LWdyYXktNDAwIGJsb2NrIG1iLTEiPlJvb3QgRm9sZGVyPC9sYWJlbD4KICAgICAgICAgICAgPHNlbGVjdCBpZD0ibW9kYWwtcm9vdCIgY2xhc3M9InctZnVsbCBiZy1ncmF5LTkwMCBib3JkZXIgYm9yZGVyLWdyYXktODAwIHJvdW5kZWQtbGcgcHgtMiBweS0xIHRleHQteHMgdGV4dC1ncmF5LTIwMCBmb2N1czpvdXRsaW5lLW5vbmUiPgogICAgICAgICAgICAgIDxvcHRpb24gdmFsdWU9Ii9zdG9yYWdlL2VtdWxhdGVkLzAvRmlzaGJvd2xNZWRpYS9Nb3ZpZXMiIHNlbGVjdGVkPkZpc2hib3dsTWVkaWEvTW92aWVzPC9vcHRpb24+CiAgICAgICAgICAgICAgPG9wdGlvbiB2YWx1ZT0iL3N0b3JhZ2UvZW11bGF0ZWQvMC9GaXNoYm93bE1lZGlhL1RWIj5GaXNoYm93bE1lZGlhL1RWPC9vcHRpb24+CiAgICAgICAgICAgIDwvc2VsZWN0PgogICAgICAgICAgPC9kaXY+CiAgICAgICAgPC9kaXY+CgogICAgICAgIDwhLS0gVFYgU2Vhc29ucyBDaGlwcyAtLT4KICAgICAgICA8ZGl2IGlkPSJtb2RhbC10di1zZWN0aW9uIiBjbGFzcz0iaGlkZGVuIHB0LTEuNSBib3JkZXItdCBib3JkZXItZ3JheS04MDAiPgogICAgICAgICAgPGxhYmVsIGNsYXNzPSJ0ZXh0LWdyYXktNDAwIGJsb2NrIG1iLTEiPlNlYXNvbnM8L2xhYmVsPgogICAgICAgICAgPGRpdiBpZD0ibW9kYWwtc2Vhc29ucy1jaGlwcyIgY2xhc3M9ImZsZXggZmxleC13cmFwIGdhcC0xIj48L2Rpdj4KICAgICAgICA8L2Rpdj4KICAgICAgPC9kaXY+CgogICAgICA8IS0tIDEtVGFwIEFjdGlvbiBFeGVjdXRpb24gQnV0dG9ucyAoUmlnaHQgaW5zaWRlIG1vZGFsISkgLS0+CiAgICAgIDxkaXYgY2xhc3M9InB0LTEgZmxleCBmbGV4LWNvbCBnYXAtMiI+CiAgICAgICAgPGJ1dHRvbiBpZD0ibW9kYWwtc3VibWl0LWJ0biIgY2xhc3M9InctZnVsbCBweS0yLjUgcm91bmRlZC14bCBiZy1icmFuZC02MDAgaG92ZXI6YmctYnJhbmQtNTAwIHRleHQtd2hpdGUgZm9udC1zZW1pYm9sZCB0ZXh0LXhzIHNtOnRleHQtc20gZmxleCBpdGVtcy1jZW50ZXIganVzdGlmeS1jZW50ZXIgZ2FwLTIgc2hhZG93LW1kIHRyYW5zaXRpb24tYWxsIGFjdGl2ZTpzY2FsZS05NSI+CiAgICAgICAgICA8c3ZnIGNsYXNzPSJ3LTQgaC00IiBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgdmlld0JveD0iMCAwIDI0IDI0Ij48cGF0aCBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZS13aWR0aD0iMiIgZD0iTTEyIDR2MTZtOC04SDQiLz48L3N2Zz4KICAgICAgICAgIDxzcGFuIGlkPSJtb2RhbC1zdWJtaXQtdGV4dCI+UmVxdWVzdCBNZWRpYTwvc3Bhbj4KICAgICAgICA8L2J1dHRvbj4KCiAgICAgICAgPGJ1dHRvbiBpZD0ibW9kYWwtY2FuY2VsLWJ0biIgY2xhc3M9ImhpZGRlbiB3LWZ1bGwgcHktMi41IHJvdW5kZWQteGwgYmctcm9zZS05NTAvNzAgaG92ZXI6Ymctcm9zZS05MDAgYm9yZGVyIGJvcmRlci1yb3NlLTUwMC80MCB0ZXh0LXJvc2UtMzAwIGZvbnQtc2VtaWJvbGQgdGV4dC14cyBmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWNlbnRlciBnYXAtMiB0cmFuc2l0aW9uLWFsbCBhY3RpdmU6c2NhbGUtOTUiPgogICAgICAgICAgPHN2ZyBjbGFzcz0idy00IGgtNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJjdXJyZW50Q29sb3IiIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2Utd2lkdGg9IjIiIGQ9Ik02IDE4TDE4IDZNNiA2bDEyIDEyIi8+PC9zdmc+CiAgICAgICAgICA8c3Bhbj5DYW5jZWwgUmVxdWVzdDwvc3Bhbj4KICAgICAgICA8L2J1dHRvbj4KICAgICAgPC9kaXY+CiAgICA8L2Rpdj4KICA8L2Rpdj4KPC9kaXY+Cgo8IS0tIE1vYmlsZSBCb3R0b20gTmF2aWdhdGlvbiBCYXIgKDEwMCUgRXF1YWwgQ29sdW1uIFdpZHRoICYgSnVzdGlmaWVkKSAtLT4KPG5hdiBjbGFzcz0ibGc6aGlkZGVuIGZpeGVkIGJvdHRvbS0wIGxlZnQtMCByaWdodC0wIHotNDAgZ2xhc3Mgc2FmZS1ib3R0b20gYm9yZGVyLXQgYm9yZGVyLWdyYXktODAwLzgwIGJuYXYtY29udGFpbmVyIHNlbGVjdC1ub25lIj4KICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYignZGlzY292ZXInKSIgY2xhc3M9ImJuYXYtaXRlbSBhY3RpdmUiIGRhdGEtdGFiPSJkaXNjb3ZlciI+CiAgICA8c3ZnIGZpbGw9Im5vbmUiIHN0cm9rZT0iY3VycmVudENvbG9yIiB2aWV3Qm94PSIwIDAgMjQgMjQiPjxwYXRoIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlLXdpZHRoPSIyIiBkPSJNMyAxMmwyLTJtMCAwbDctNyA3IDdNNSAxMHYxMGExIDEgMCAwMDEgMWgzbTEwLTExbDIgMm0tMi0ydjEwYTEgMSAwIDAxLTEgMWgtM20tNiAwYTEgMSAwIDAwMS0xdi00YTEgMSAwIDAxMS0xaDJhMSAxIDAgMDExIDF2NGExIDEgMCAwMDEgMW0tNiAwaDYiLz48L3N2Zz4KICAgIDxzcGFuPkRpc2NvdmVyPC9zcGFuPgogIDwvYnV0dG9uPgogIDxidXR0b24gb25jbGljaz0ic3dpdGNoVGFiKCdtb3ZpZXMnKSIgY2xhc3M9ImJuYXYtaXRlbSIgZGF0YS10YWI9Im1vdmllcyI+CiAgICA8c3ZnIGZpbGw9Im5vbmUiIHN0cm9rZT0iY3VycmVudENvbG9yIiB2aWV3Qm94PSIwIDAgMjQgMjQiPjxwYXRoIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlLXdpZHRoPSIyIiBkPSJNNyA0djE2TTE3IDR2MTZNMyA4aDRtMTAgMGg0TTMgMTJoMThNMyAxNmg0bTEwIDBoNE00IDIwaDE2YTEgMSAwIDAwMS0xVjVhMSAxIDAgMDAtMS0xSDRhMSAxIDAgMDAtMSAxdjE0YTEgMSAwIDAwMSAxeiIvPjwvc3ZnPgogICAgPHNwYW4+TW92aWVzPC9zcGFuPgogIDwvYnV0dG9uPgogIDxidXR0b24gb25jbGljaz0ic3dpdGNoVGFiKCd0dicpIiBjbGFzcz0iYm5hdi1pdGVtIiBkYXRhLXRhYj0idHYiPgogICAgPHN2ZyBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgdmlld0JveD0iMCAwIDI0IDI0Ij48cGF0aCBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZS13aWR0aD0iMiIgZD0iTTkuNzUgMTdMOSAyMGwtMSAxaDhsLTEtMS0uNzUtM00zIDEzaDE4TTUgMTdoMTRhMiAyIDAgMDAyLTJWNWEyIDIgMCAwMC0yLTJINWEyIDIgMCAwMC0yIDJ2MTBhMiAyIDAgMDAyIDJ6Ii8+PC9zdmc+CiAgICA8c3Bhbj5UViBTaG93czwvc3Bhbj4KICA8L2J1dHRvbj4KICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYigncmVxdWVzdHMnKSIgY2xhc3M9ImJuYXYtaXRlbSByZWxhdGl2ZSIgZGF0YS10YWI9InJlcXVlc3RzIj4KICAgIDxzdmcgZmlsbD0ibm9uZSIgc3Ryb2tlPSJjdXJyZW50Q29sb3IiIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2Utd2lkdGg9IjIiIGQ9Ik05IDVIN2EyIDIgMCAwMC0yIDJ2MTJhMiAyIDAgMDAyIDJoMTBhMiAyIDAgMDAyLTJWN2EyIDIgMCAwMC0yLTJoLTJNOSA1YTIgMiAwIDAwMiAyaDJhMiAyIDAgMDAyLTJNOSA1YTIgMiAwIDAxMi0yaDJhMiAyIDAgMDEyIDJtLTYgOWwyIDIgNC00Ii8+PC9zdmc+CiAgICA8c3Bhbj5SZXF1ZXN0czwvc3Bhbj4KICAgIDxzcGFuIGlkPSJtb2JpbGUtcmVxLWJhZGdlIiBjbGFzcz0iaGlkZGVuIGFic29sdXRlIHRvcC0xIHJpZ2h0LTEvNCB3LTIgaC0yIHJvdW5kZWQtZnVsbCBiZy1icmFuZC01MDAiPjwvc3Bhbj4KICA8L2J1dHRvbj4KICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYignZG93bmxvYWRzJykiIGNsYXNzPSJibmF2LWl0ZW0iIGRhdGEtdGFiPSJkb3dubG9hZHMiPgogICAgPHN2ZyBmaWxsPSJub25lIiBzdHJva2U9ImN1cnJlbnRDb2xvciIgdmlld0JveD0iMCAwIDI0IDI0Ij48cGF0aCBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZS13aWR0aD0iMiIgZD0iTTQgMTZ2MWEzIDMgMCAwMDMgM2gxMGEzIDMgMCAwMDMtM3YtMW0tNC00bC00IDRtMCAwbC00LTRtNCA0VjQiLz48L3N2Zz4KICAgIDxzcGFuPkRvd25sb2Fkczwvc3Bhbj4KICA8L2J1dHRvbj4KICA8YnV0dG9uIG9uY2xpY2s9InN3aXRjaFRhYignc2VydmljZXMnKSIgY2xhc3M9ImJuYXYtaXRlbSIgZGF0YS10YWI9InNlcnZpY2VzIj4KICAgIDxzdmcgZmlsbD0ibm9uZSIgc3Ryb2tlPSJjdXJyZW50Q29sb3IiIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2Utd2lkdGg9IjIiIGQ9Ik01IDEyaDE0TTUgMTJhMiAyIDAgMDEtMi0yVjZhMiAyIDAgMDEyLTJoMTRhMiAyIDAgMDEyIDJ2NGEyIDIgMCAwMS0yIDJNNSAxMmEyIDIgMCAwMC0yIDJ2NGEyIDIgMCAwMDIgMmgxNGEyIDIgMCAwMDItMnYtNGEyIDIgMCAwMC0yLTJtLTItNGguMDFNMTcgMTZoLjAxIi8+PC9zdmc+CiAgICA8c3Bhbj5TZXJ2aWNlczwvc3Bhbj4KICA8L2J1dHRvbj4KPC9uYXY+Cgo8IS0tIFNldHRpbmdzIE1vZGFsIC0tPgo8ZGl2IGlkPSJzZXR0aW5ncy1tb2RhbCIgY2xhc3M9ImhpZGRlbiBmaXhlZCBpbnNldC0wIHotNTAgZmxleCBpdGVtcy1jZW50ZXIganVzdGlmeS1jZW50ZXIgcC00IGJnLWJsYWNrLzgwIGJhY2tkcm9wLWJsdXItc20iPgogIDxkaXYgY2xhc3M9ImdsYXNzLWNhcmQgdy1mdWxsIG1heC13LXNtIHJvdW5kZWQtMnhsIHAtNCBzcGFjZS15LTMgc2hhZG93LTJ4bCI+CiAgICA8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4iPgogICAgICA8aDMgY2xhc3M9InRleHQtc20gZm9udC1ib2xkIHRleHQtd2hpdGUiPkNvbm5lY3Rpb24gRW5kcG9pbnQ8L2gzPgogICAgICA8YnV0dG9uIGlkPSJzZXR0aW5ncy1jbG9zZS1idG4iIGNsYXNzPSJ0ZXh0LWdyYXktNDAwIGhvdmVyOnRleHQtd2hpdGUiPgogICAgICAgIDxzdmcgY2xhc3M9InctNCBoLTQiIGZpbGw9Im5vbmUiIHN0cm9rZT0iY3VycmVudENvbG9yIiB2aWV3Qm94PSIwIDAgMjQgMjQiPjxwYXRoIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlLXdpZHRoPSIyIiBkPSJNNiAxOEwxOCA2TTYgNmwxMiAxMiIvPjwvc3ZnPgogICAgICA8L2J1dHRvbj4KICAgIDwvZGl2PgogICAgPGRpdiBjbGFzcz0ic3BhY2UteS0yIj4KICAgICAgPGRpdj4KICAgICAgICA8bGFiZWwgY2xhc3M9InRleHQtWzExcHhdIHRleHQtZ3JheS00MDAgYmxvY2sgbWItMSI+U2VydmVyIEhvc3Q8L2xhYmVsPgogICAgICAgIDxpbnB1dCBpZD0iY2ZnLWhvc3QiIHR5cGU9InRleHQiIHBsYWNlaG9sZGVyPSJodHRwOi8vMTI3LjAuMC4xOjUwNTUiIGNsYXNzPSJ3LWZ1bGwgYmctZ3JheS05MDAgYm9yZGVyIGJvcmRlci1ncmF5LTgwMCByb3VuZGVkLWxnIHB4LTIuNSBweS0xLjUgdGV4dC14cyB0ZXh0LWdyYXktMjAwIGZvbnQtbW9ubyIgLz4KICAgICAgPC9kaXY+CiAgICAgIDxkaXY+CiAgICAgICAgPGxhYmVsIGNsYXNzPSJ0ZXh0LVsxMXB4XSB0ZXh0LWdyYXktNDAwIGJsb2NrIG1iLTEiPkFQSSBLZXk8L2xhYmVsPgogICAgICAgIDxpbnB1dCBpZD0iY2ZnLWFwaS1rZXkiIHR5cGU9InBhc3N3b3JkIiBwbGFjZWhvbGRlcj0iT3B0aW9uYWwiIGNsYXNzPSJ3LWZ1bGwgYmctZ3JheS05MDAgYm9yZGVyIGJvcmRlci1ncmF5LTgwMCByb3VuZGVkLWxnIHB4LTIuNSBweS0xLjUgdGV4dC14cyB0ZXh0LWdyYXktMjAwIGZvbnQtbW9ubyIgLz4KICAgICAgPC9kaXY+CiAgICA8L2Rpdj4KICAgIDxkaXYgY2xhc3M9InB0LTIgYm9yZGVyLXQgYm9yZGVyLWdyYXktODAwIGZsZXgganVzdGlmeS1lbmQgZ2FwLTIiPgogICAgICA8YnV0dG9uIGlkPSJjZmctcmVzZXQtYnRuIiBjbGFzcz0icHgtMi41IHB5LTEgdGV4dC14cyB0ZXh0LWdyYXktNDAwIGhvdmVyOnRleHQtd2hpdGUiPlJlc2V0PC9idXR0b24+CiAgICAgIDxidXR0b24gaWQ9ImNmZy1zYXZlLWJ0biIgY2xhc3M9InB4LTMuNSBweS0xLjUgYmctYnJhbmQtNjAwIGhvdmVyOmJnLWJyYW5kLTUwMCB0ZXh0LXdoaXRlIHJvdW5kZWQtbGcgdGV4dC14cyBmb250LXNlbWlib2xkIj5TYXZlPC9idXR0b24+CiAgICA8L2Rpdj4KICA8L2Rpdj4KPC9kaXY+Cgo8IS0tIFRvYXN0cyAtLT4KPGRpdiBpZD0idG9hc3QtY29udGFpbmVyIiBjbGFzcz0iZml4ZWQgYm90dG9tLTE2IGxnOmJvdHRvbS00IHJpZ2h0LTQgei01MCBmbGV4IGZsZXgtY29sIGdhcC0xLjUgcG9pbnRlci1ldmVudHMtbm9uZSBtYXgtdy14cyI+PC9kaXY+Cgo8c2NyaXB0PgpmdW5jdGlvbiByZXNvbHZlQmFzZVVybCgpIHsKICBjb25zdCBjdXN0b20gPSBsb2NhbFN0b3JhZ2UuZ2V0SXRlbSgnamVsbHlzZWVycl9ob3N0Jyk7CiAgaWYgKGN1c3RvbSAmJiBjdXN0b20udHJpbSgpLmxlbmd0aCA+IDApIHJldHVybiBjdXN0b20udHJpbSgpLnJlcGxhY2UoL1wvKyQvLCAnJyk7CiAgY29uc3QgbG9jID0gd2luZG93LmxvY2F0aW9uOwogIGlmIChsb2MgJiYgbG9jLnBvcnQgPT09ICc1MDU1JykgcmV0dXJuIGxvYy5vcmlnaW47CiAgaWYgKCFsb2MgfHwgIWxvYy5ob3N0bmFtZSB8fCBsb2MuaG9zdG5hbWUgPT09ICdsb2NhbGhvc3QnIHx8IGxvYy5ob3N0bmFtZSA9PT0gJzEyNy4wLjAuMScpIHsKICAgIHJldHVybiAnaHR0cDovLzEyNy4wLjAuMTo1MDU1JzsKICB9CiAgcmV0dXJuICdodHRwOi8vJyArIGxvYy5ob3N0bmFtZSArICc6NTA1NSc7Cn0KCmxldCBCQVNFX1VSTCA9IHJlc29sdmVCYXNlVXJsKCk7CmxldCBoZXJvSXRlbSA9IG51bGw7CmxldCBhY3RpdmVNb2RhbEl0ZW0gPSBudWxsOwpsZXQgY3VycmVudFRyZW5kaW5nID0gW107CmxldCBjdXJyZW50TW92aWVzID0gW107CmxldCBjdXJyZW50VHYgPSBbXTsKbGV0IGxpYnJhcnlNb3ZpZXMgPSBbXTsKbGV0IGxpYnJhcnlTZXJpZXMgPSBbXTsKbGV0IGFjdGl2ZVJlcXVlc3RzTGlzdCA9IFtdOwpsZXQgcmFkYXJyUXVhbGl0eVByb2ZpbGVzID0gW107CmxldCBzb25hcnJRdWFsaXR5UHJvZmlsZXMgPSBbXTsKbGV0IHJhZGFyclJvb3RGb2xkZXJzID0gW107CmxldCBzb25hcnJSb290Rm9sZGVycyA9IFtdOwpsZXQgbW9kYWxTZWxlY3RlZFNlYXNvbnMgPSBbXTsKCi8vIEZhbGxiYWNrIFNWRyBQb3N0ZXIgR2VuZXJhdG9yCmZ1bmN0aW9uIGdlbmVyYXRlU3ZnUG9zdGVyKHRpdGxlLCB5ZWFyKSB7CiAgY29uc3Qgc2FmZVRpdGxlID0gKHRpdGxlIHx8ICdNZWRpYScpLnJlcGxhY2UoL1s8PiYiXS9nLCAnJyk7CiAgY29uc3Qgc2FmZVllYXIgPSB5ZWFyIHx8ICcnOwogIHJldHVybiAnZGF0YTppbWFnZS9zdmcreG1sO2NoYXJzZXQ9dXRmLTgsJyArIGVuY29kZVVSSUNvbXBvbmVudCgKICAgICc8c3ZnIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgd2lkdGg9IjMwMCIgaGVpZ2h0PSI0NTAiIHZpZXdCb3g9IjAgMCAzMDAgNDUwIj4nICsKICAgICAgJzxkZWZzPjxsaW5lYXJHcmFkaWVudCBpZD0iYmciIHgxPSIwJSIgeTE9IjAlIiB4Mj0iMTAwJSIgeTI9IjEwMCUiPicgKwogICAgICAnPHN0b3Agb2Zmc2V0PSIwJSIgc3RvcC1jb2xvcj0iIzBmMTcyYSIgLz4nICsKICAgICAgJzxzdG9wIG9mZnNldD0iMTAwJSIgc3RvcC1jb2xvcj0iIzFlMjkzYiIgLz4nICsKICAgICAgJzwvbGluZWFyR3JhZGllbnQ+PC9kZWZzPicgKwogICAgICAnPHJlY3Qgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSIgZmlsbD0idXJsKCNiZykiIC8+JyArCiAgICAgICc8cmVjdCB4PSIxMiIgeT0iMTIiIHdpZHRoPSIyNzYiIGhlaWdodD0iNDI2IiByeD0iMTAiIGZpbGw9Im5vbmUiIHN0cm9rZT0icmdiYSgyNTUsMjU1LDI1NSwwLjA4KSIgc3Ryb2tlLXdpZHRoPSIyIi8+JyArCiAgICAgICc8Y2lyY2xlIGN4PSIxNTAiIGN5PSIxODAiIHI9IjQwIiBmaWxsPSJub25lIiBzdHJva2U9IiM2MzY2ZjEiIHN0cm9rZS13aWR0aD0iMi41IiBvcGFjaXR5PSIwLjQiLz4nICsKICAgICAgJzx0ZXh0IHg9IjE1MCIgeT0iMTg3IiBmaWxsPSIjNjM2NmYxIiBmb250LXNpemU9IjE4IiBmb250LWZhbWlseT0ic2Fucy1zZXJpZiIgZm9udC13ZWlnaHQ9ImJvbGQiIHRleHQtYW5jaG9yPSJtaWRkbGUiPk1FRElBPC90ZXh0PicgKwogICAgICAnPHRleHQgeD0iMTUwIiB5PSIyNzAiIGZpbGw9IiNmOGZhZmMiIGZvbnQtc2l6ZT0iMTQiIGZvbnQtZmFtaWx5PSJzYW5zLXNlcmlmIiBmb250LXdlaWdodD0iYm9sZCIgdGV4dC1hbmNob3I9Im1pZGRsZSI+JyArIHNhZmVUaXRsZS5zdWJzdHJpbmcoMCwgMTgpICsgJzwvdGV4dD4nICsKICAgICAgJzx0ZXh0IHg9IjE1MCIgeT0iMjk1IiBmaWxsPSIjOTRhM2I4IiBmb250LXNpemU9IjEyIiBmb250LWZhbWlseT0ic2Fucy1zZXJpZiIgdGV4dC1hbmNob3I9Im1pZGRsZSI+JyArIHNhZmVZZWFyICsgJzwvdGV4dD4nICsKICAgICc8L3N2Zz4nCiAgKTsKfQoKZnVuY3Rpb24gaGFuZGxlSW1nRXJyb3IoaW1nLCB0aXRsZSwgeWVhcikgewogIGltZy5vbmVycm9yID0gbnVsbDsKICBpbWcuc3JjID0gZ2VuZXJhdGVTdmdQb3N0ZXIodGl0bGUsIHllYXIpOwp9CgpmdW5jdGlvbiBnZXRQb3N0ZXIoaXRlbSkgewogIGNvbnN0IHAgPSBpdGVtLnBvc3Rlcl9wYXRoIHx8IGl0ZW0ucG9zdGVyUGF0aDsKICBpZiAocCAmJiBwLnN0YXJ0c1dpdGgoJy8nKSkgewogICAgcmV0dXJuICdodHRwczovL2ltYWdlLnRtZGIub3JnL3QvcC93NTAwJyArIHA7CiAgfQogIGlmIChwICYmIHAuc3RhcnRzV2l0aCgnaHR0cCcpKSB7CiAgICByZXR1cm4gcDsKICB9CiAgaWYgKGl0ZW0ucmVtb3RlUG9zdGVyICYmIGl0ZW0ucmVtb3RlUG9zdGVyLmluY2x1ZGVzKCdodHRwJykpIHsKICAgIHJldHVybiBpdGVtLnJlbW90ZVBvc3RlcjsKICB9CiAgaWYgKGl0ZW0uYmFja2Ryb3AgJiYgaXRlbS5iYWNrZHJvcC5pbmNsdWRlcygnaHR0cCcpKSB7CiAgICByZXR1cm4gaXRlbS5iYWNrZHJvcDsKICB9CiAgcmV0dXJuIGdlbmVyYXRlU3ZnUG9zdGVyKGdldEl0ZW1UaXRsZShpdGVtKSwgZ2V0SXRlbVllYXIoaXRlbSkpOwp9CgpmdW5jdGlvbiBnZXRCYWNrZHJvcChpdGVtKSB7CiAgY29uc3QgYiA9IGl0ZW0uYmFja2Ryb3BfcGF0aCB8fCBpdGVtLmJhY2tkcm9wUGF0aDsKICBpZiAoYiAmJiBiLnN0YXJ0c1dpdGgoJy8nKSkgewogICAgcmV0dXJuICdodHRwczovL2ltYWdlLnRtZGIub3JnL3QvcC9vcmlnaW5hbCcgKyBiOwogIH0KICBpZiAoYiAmJiBiLnN0YXJ0c1dpdGgoJ2h0dHAnKSkgewogICAgcmV0dXJuIGI7CiAgfQogIGlmIChpdGVtLmJhY2tkcm9wICYmIGl0ZW0uYmFja2Ryb3AuaW5jbHVkZXMoJ2h0dHAnKSkgewogICAgcmV0dXJuIGl0ZW0uYmFja2Ryb3A7CiAgfQogIGlmIChpdGVtLnJlbW90ZVBvc3RlciAmJiBpdGVtLnJlbW90ZVBvc3Rlci5pbmNsdWRlcygnaHR0cCcpKSB7CiAgICByZXR1cm4gaXRlbS5yZW1vdGVQb3N0ZXIucmVwbGFjZSgvXC93XGQrXC8vLCAnL29yaWdpbmFsLycpOwogIH0KICByZXR1cm4gJyc7Cn0KCmZ1bmN0aW9uIGdldEl0ZW1UaXRsZShpdGVtKSB7CiAgcmV0dXJuIGl0ZW0udGl0bGUgfHwgaXRlbS5uYW1lIHx8IGl0ZW0ub3JpZ2luYWxfdGl0bGUgfHwgaXRlbS5vcmlnaW5hbF9uYW1lIHx8ICdVbnRpdGxlZCc7Cn0KCmZ1bmN0aW9uIGdldEl0ZW1ZZWFyKGl0ZW0pIHsKICBjb25zdCBkID0gaXRlbS5yZWxlYXNlX2RhdGUgfHwgaXRlbS5maXJzdF9haXJfZGF0ZSB8fCBpdGVtLnJlbGVhc2VEYXRlIHx8IGl0ZW0uZmlyc3RBaXJEYXRlIHx8IGl0ZW0ueWVhciB8fCAnJzsKICByZXR1cm4gU3RyaW5nKGQpLnN1YnN0cmluZygwLCA0KTsKfQoKZnVuY3Rpb24gZ2V0SXRlbVR5cGUoaXRlbSkgewogIHJldHVybiBpdGVtLm1lZGlhX3R5cGUgfHwgaXRlbS50eXBlIHx8IGl0ZW0ubWVkaWFUeXBlIHx8IChpdGVtLm5hbWUgPyAndHYnIDogJ21vdmllJyk7Cn0KCmZ1bmN0aW9uIHNob3dUb2FzdChtZXNzYWdlLCB0eXBlID0gJ2luZm8nKSB7CiAgY29uc3QgY29udGFpbmVyID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ3RvYXN0LWNvbnRhaW5lcicpOwogIGlmICghY29udGFpbmVyKSByZXR1cm47CiAgY29uc3QgdG9hc3QgPSBkb2N1bWVudC5jcmVhdGVFbGVtZW50KCdkaXYnKTsKICBjb25zdCBib3JkZXJDb2wgPSB0eXBlID09PSAnc3VjY2VzcycgPyAnYm9yZGVyLWVtZXJhbGQtNTAwLzQwIHRleHQtZW1lcmFsZC0zMDAnIDoKICAgICAgICAgICAgICAgICAgICB0eXBlID09PSAnZXJyb3InICAgPyAnYm9yZGVyLXJvc2UtNTAwLzQwIHRleHQtcm9zZS0zMDAnIDoKICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAnYm9yZGVyLWJyYW5kLTUwMC80MCB0ZXh0LWJyYW5kLTMwMCc7CiAgdG9hc3QuY2xhc3NOYW1lID0gJ2dsYXNzIHB4LTMgcHktMiByb3VuZGVkLXhsIGJvcmRlciAnICsgYm9yZGVyQ29sICsgJyB0ZXh0LXhzIGZvbnQtbWVkaXVtIHNoYWRvdy0yeGwgZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTIgcG9pbnRlci1ldmVudHMtYXV0byBhbmltYXRlLXRvYXN0IGJnLWdyYXktOTUwLzk1JzsKICB0b2FzdC5pbm5lckhUTUwgPSAnPHNwYW4+JyArIG1lc3NhZ2UgKyAnPC9zcGFuPic7CiAgY29udGFpbmVyLmFwcGVuZENoaWxkKHRvYXN0KTsKICBzZXRUaW1lb3V0KCgpID0+IHsKICAgIHRvYXN0LnN0eWxlLm9wYWNpdHkgPSAnMCc7CiAgICB0b2FzdC5zdHlsZS50cmFuc2l0aW9uID0gJ29wYWNpdHkgMC4yNXMgZWFzZSc7CiAgICBzZXRUaW1lb3V0KCgpID0+IHRvYXN0LnJlbW92ZSgpLCAyNTApOwogIH0sIDI4MDApOwp9CgpmdW5jdGlvbiBnZXRJdGVtTGlicmFyeVN0YXR1cyhpdGVtKSB7CiAgY29uc3QgdHlwZSA9IGdldEl0ZW1UeXBlKGl0ZW0pOwogIGNvbnN0IHRpdGxlTG93ID0gZ2V0SXRlbVRpdGxlKGl0ZW0pLnRvTG93ZXJDYXNlKCk7CgogIGNvbnN0IHJlcU1hdGNoID0gYWN0aXZlUmVxdWVzdHNMaXN0LmZpbmQociA9PiAKICAgIChpdGVtLnRtZGJJZCAmJiByLnRtZGJJZCA9PT0gaXRlbS50bWRiSWQpIHx8IAogICAgKGl0ZW0udHZkYklkICYmIHIudHZkYklkID09PSBpdGVtLnR2ZGJJZCkgfHwgCiAgICAoci50aXRsZSAmJiByLnRpdGxlLnRvTG93ZXJDYXNlKCkgPT09IHRpdGxlTG93KQogICk7CiAgaWYgKHJlcU1hdGNoKSB7CiAgICBpZiAocmVxTWF0Y2guc3RhdHVzID09PSAncGVuZGluZycpIHsKICAgICAgcmV0dXJuIHsgbGFiZWw6ICdQZW5kaW5nIE9mZmxpbmUnLCBjc3NDbGFzczogJ2JnLWFtYmVyLTUwMC8yMCB0ZXh0LWFtYmVyLTMwMCBib3JkZXIgYm9yZGVyLWFtYmVyLTUwMC80MCcsIGlzUmVxdWVzdGVkOiB0cnVlLCBpc1BlbmRpbmc6IHRydWUsIGlzQXZhaWxhYmxlOiBmYWxzZSwgcmVxdWVzdElkOiByZXFNYXRjaC5pZCB9OwogICAgfQogICAgaWYgKHJlcU1hdGNoLnN0YXR1cyA9PT0gJ2luX2xpYnJhcnknKSB7CiAgICAgIHJldHVybiB7IGxhYmVsOiAnSW4gTGlicmFyeScsIGNzc0NsYXNzOiAnYmFkZ2UtYXZhaWxhYmxlJywgaXNSZXF1ZXN0ZWQ6IGZhbHNlLCBpc0F2YWlsYWJsZTogdHJ1ZSB9OwogICAgfQogICAgcmV0dXJuIHsgbGFiZWw6ICdSZXF1ZXN0ZWQnLCBjc3NDbGFzczogJ2JhZGdlLXJlcXVlc3RlZCcsIGlzUmVxdWVzdGVkOiB0cnVlLCBpc0F2YWlsYWJsZTogZmFsc2UsIHJlcXVlc3RJZDogcmVxTWF0Y2guaWQgfTsKICB9CgogIGlmICh0eXBlID09PSAnbW92aWUnKSB7CiAgICBjb25zdCBtYXRjaCA9IGxpYnJhcnlNb3ZpZXMuZmluZChtID0+IChpdGVtLnRtZGJJZCAmJiBtLnRtZGJJZCA9PT0gaXRlbS50bWRiSWQpIHx8IChtLnRpdGxlICYmIG0udGl0bGUudG9Mb3dlckNhc2UoKSA9PT0gdGl0bGVMb3cpKTsKICAgIGlmIChtYXRjaCkgewogICAgICBpZiAobWF0Y2guaGFzRmlsZSkgcmV0dXJuIHsgbGFiZWw6ICdJbiBMaWJyYXJ5JywgY3NzQ2xhc3M6ICdiYWRnZS1hdmFpbGFibGUnLCBpc0F2YWlsYWJsZTogdHJ1ZSB9OwogICAgICByZXR1cm4geyBsYWJlbDogJ1Byb2Nlc3NpbmcnLCBjc3NDbGFzczogJ2JhZGdlLXByb2Nlc3NpbmcnLCBpc0F2YWlsYWJsZTogZmFsc2UgfTsKICAgIH0KICB9IGVsc2UgaWYgKHR5cGUgPT09ICd0dicpIHsKICAgIGNvbnN0IG1hdGNoID0gbGlicmFyeVNlcmllcy5maW5kKHMgPT4gKGl0ZW0udHZkYklkICYmIHMudHZkYklkID09PSBpdGVtLnR2ZGJJZCkgfHwgKHMudGl0bGUgJiYgcy50aXRsZS50b0xvd2VyQ2FzZSgpID09PSB0aXRsZUxvdykpOwogICAgaWYgKG1hdGNoKSB7CiAgICAgIGNvbnN0IGlzQ29tcGxldGUgPSBtYXRjaC5wZXJjZW50T2ZFcGlzb2RlcyA9PT0gMTAwIHx8IChtYXRjaC5zdGF0aXN0aWNzICYmIG1hdGNoLnN0YXRpc3RpY3MucGVyY2VudE9mRXBpc29kZXMgPT09IDEwMCk7CiAgICAgIGlmIChpc0NvbXBsZXRlKSByZXR1cm4geyBsYWJlbDogJ0luIExpYnJhcnknLCBjc3NDbGFzczogJ2JhZGdlLWF2YWlsYWJsZScsIGlzQXZhaWxhYmxlOiB0cnVlIH07CiAgICAgIHJldHVybiB7IGxhYmVsOiAnUmVxdWVzdGVkJywgY3NzQ2xhc3M6ICdiYWRnZS1yZXF1ZXN0ZWQnLCBpc0F2YWlsYWJsZTogZmFsc2UgfTsKICAgIH0KICB9CiAgcmV0dXJuIG51bGw7Cn0KCmFzeW5jIGZ1bmN0aW9uIGFwaUdldChlbmRwb2ludCkgewogIGNvbnN0IGFwaUtleSA9IGxvY2FsU3RvcmFnZS5nZXRJdGVtKCdqZWxseXNlZXJyX2FwaV9rZXknKTsKICBjb25zdCBoZWFkZXJzID0geyAnQWNjZXB0JzogJ2FwcGxpY2F0aW9uL2pzb24nIH07CiAgaWYgKGFwaUtleSkgaGVhZGVyc1snWC1BcGktS2V5J10gPSBhcGlLZXk7CiAgY29uc3QgcmVzID0gYXdhaXQgZmV0Y2goQkFTRV9VUkwgKyBlbmRwb2ludCwgeyBoZWFkZXJzIH0pOwogIGlmICghcmVzLm9rKSB0aHJvdyBuZXcgRXJyb3IoJ0hUVFAgJyArIHJlcy5zdGF0dXMpOwogIHJldHVybiBhd2FpdCByZXMuanNvbigpOwp9Cgphc3luYyBmdW5jdGlvbiBhcGlQb3N0KGVuZHBvaW50LCBwYXlsb2FkKSB7CiAgY29uc3QgYXBpS2V5ID0gbG9jYWxTdG9yYWdlLmdldEl0ZW0oJ2plbGx5c2VlcnJfYXBpX2tleScpOwogIGNvbnN0IGhlYWRlcnMgPSB7ICdDb250ZW50LVR5cGUnOiAnYXBwbGljYXRpb24vanNvbicsICdBY2NlcHQnOiAnYXBwbGljYXRpb24vanNvbicgfTsKICBpZiAoYXBpS2V5KSBoZWFkZXJzWydYLUFwaS1LZXknXSA9IGFwaUtleTsKICBjb25zdCByZXMgPSBhd2FpdCBmZXRjaChCQVNFX1VSTCArIGVuZHBvaW50LCB7CiAgICBtZXRob2Q6ICdQT1NUJywKICAgIGhlYWRlcnM6IGhlYWRlcnMsCiAgICBib2R5OiBKU09OLnN0cmluZ2lmeShwYXlsb2FkKQogIH0pOwogIGNvbnN0IGRhdGEgPSBhd2FpdCByZXMuanNvbigpLmNhdGNoKCgpID0+ICh7fSkpOwogIGlmICghcmVzLm9rKSB7CiAgICBjb25zdCBtc2cgPSAoZGF0YSAmJiBkYXRhLm1lc3NhZ2UpID8gZGF0YS5tZXNzYWdlIDogKCdIVFRQICcgKyByZXMuc3RhdHVzKTsKICAgIHRocm93IG5ldyBFcnJvcihtc2cpOwogIH0KICByZXR1cm4gZGF0YTsKfQoKZnVuY3Rpb24gc3dpdGNoVGFiKHRhYikgewogIGNvbnN0IHRhYnMgPSBbJ2Rpc2NvdmVyJywgJ21vdmllcycsICd0dicsICdyZXF1ZXN0cycsICdzZXJ2aWNlcycsICdkb3dubG9hZHMnXTsKICB0YWJzLmZvckVhY2godCA9PiB7CiAgICBjb25zdCB2aWV3ID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ3ZpZXctJyArIHQpOwogICAgaWYgKHZpZXcpIHZpZXcuY2xhc3NMaXN0LmFkZCgnaGlkZGVuJyk7CiAgICBkb2N1bWVudC5xdWVyeVNlbGVjdG9yQWxsKCcubmF2LXBpbGxbZGF0YS10YWI9IicgKyB0ICsgJyJdJykuZm9yRWFjaChiID0+IGIuY2xhc3NMaXN0LnJlbW92ZSgnYWN0aXZlJykpOwogICAgZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnLmJuYXYtaXRlbVtkYXRhLXRhYj0iJyArIHQgKyAnIl0nKS5mb3JFYWNoKGIgPT4gYi5jbGFzc0xpc3QucmVtb3ZlKCdhY3RpdmUnKSk7CiAgfSk7CgogIGNvbnN0IGFjdGl2ZVZpZXcgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgndmlldy0nICsgdGFiKTsKICBpZiAoYWN0aXZlVmlldykgYWN0aXZlVmlldy5jbGFzc0xpc3QucmVtb3ZlKCdoaWRkZW4nKTsKCiAgZG9jdW1lbnQucXVlcnlTZWxlY3RvckFsbCgnLm5hdi1waWxsW2RhdGEtdGFiPSInICsgdGFiICsgJyJdJykuZm9yRWFjaChiID0+IGIuY2xhc3NMaXN0LmFkZCgnYWN0aXZlJykpOwogIGRvY3VtZW50LnF1ZXJ5U2VsZWN0b3JBbGwoJy5ibmF2LWl0ZW1bZGF0YS10YWI9IicgKyB0YWIgKyAnIl0nKS5mb3JFYWNoKGIgPT4gYi5jbGFzc0xpc3QuYWRkKCdhY3RpdmUnKSk7CgogIHdpbmRvdy5zY3JvbGxUbyh7IHRvcDogMCwgYmVoYXZpb3I6ICdzbW9vdGgnIH0pOwoKICBpZiAodGFiID09PSAncmVxdWVzdHMnKSBmZXRjaFJlcXVlc3RzTGlzdCgpOwogIGlmICh0YWIgPT09ICdzZXJ2aWNlcycpIGZldGNoU2VydmljZXNMaXN0KCk7CiAgaWYgKHRhYiA9PT0gJ2Rvd25sb2FkcycpIGZldGNoRG93bmxvYWRzKCk7CiAgaWYgKHRhYiA9PT0gJ21vdmllcycgJiYgY3VycmVudE1vdmllcy5sZW5ndGggPiAwKSByZW5kZXJHcmlkKCdtb3ZpZXMtZnVsbC1ncmlkJywgY3VycmVudE1vdmllcyk7CiAgaWYgKHRhYiA9PT0gJ3R2JyAmJiBjdXJyZW50VHYubGVuZ3RoID4gMCkgcmVuZGVyR3JpZCgndHYtZnVsbC1ncmlkJywgY3VycmVudFR2KTsKfQoKLy8gTWFzdGVyIExvYWRlcgphc3luYyBmdW5jdGlvbiBsb2FkKCkgewogIGNvbnN0IGhvc3RCYWRnZSA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdob3N0LWJhZGdlJyk7CiAgaWYgKGhvc3RCYWRnZSkgaG9zdEJhZGdlLnRleHRDb250ZW50ID0gQkFTRV9VUkwucmVwbGFjZSgvaHR0cHM/OlwvXC8vLCAnJyk7CgogIGF3YWl0IFByb21pc2UuYWxsU2V0dGxlZChbCiAgICBmZXRjaFJlcXVlc3RzTGlzdCgpLAogICAgZmV0Y2hMaWJyYXJ5KCksCiAgICBmZXRjaFRyZW5kaW5nKCksCiAgICBmZXRjaE1vdmllcygpLAogICAgZmV0Y2hUdigpLAogICAgZmV0Y2hQcm9maWxlc0FuZEZvbGRlcnMoKSwKICAgIGZldGNoU2VydmljZXNMaXN0KCksCiAgICBmZXRjaERvd25sb2FkcygpCiAgXSk7Cn0KCmFzeW5jIGZ1bmN0aW9uIGZldGNoVHJlbmRpbmcoKSB7CiAgdHJ5IHsKICAgIGNvbnN0IGRhdGEgPSBhd2FpdCBhcGlHZXQoJy9hcGkvdjEvZGlzY292ZXIvdHJlbmRpbmcnKTsKICAgIGNvbnN0IGl0ZW1zID0gKGRhdGEgJiYgZGF0YS5yZXN1bHRzKSA/IGRhdGEucmVzdWx0cyA6IChBcnJheS5pc0FycmF5KGRhdGEpID8gZGF0YSA6IFtdKTsKICAgIGN1cnJlbnRUcmVuZGluZyA9IGl0ZW1zOwogICAgaWYgKGl0ZW1zLmxlbmd0aCA+IDAgJiYgIWhlcm9JdGVtKSB7CiAgICAgIGhlcm9JdGVtID0gaXRlbXNbMF07CiAgICAgIHJlbmRlckhlcm8oaXRlbXNbMF0pOwogICAgfQogICAgcmVuZGVyR3JpZCgndHJlbmRpbmctZ3JpZCcsIGl0ZW1zLnNsaWNlKDAsIDE2KSk7CiAgfSBjYXRjaCAoZXJyKSB7fQp9Cgphc3luYyBmdW5jdGlvbiBmZXRjaE1vdmllcygpIHsKICB0cnkgewogICAgY29uc3QgZGF0YSA9IGF3YWl0IGFwaUdldCgnL2FwaS92MS9kaXNjb3Zlci9tb3ZpZXMnKTsKICAgIGN1cnJlbnRNb3ZpZXMgPSAoZGF0YSAmJiBkYXRhLnJlc3VsdHMpID8gZGF0YS5yZXN1bHRzIDogKEFycmF5LmlzQXJyYXkoZGF0YSkgPyBkYXRhIDogW10pOwogICAgcmVuZGVyR3JpZCgnbW92aWVzLXByZXZpZXctZ3JpZCcsIGN1cnJlbnRNb3ZpZXMuc2xpY2UoMCwgOCkpOwogICAgY29uc3QgY291bnRFbCA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdtb3ZpZXMtZnVsbC1jb3VudCcpOwogICAgaWYgKGNvdW50RWwpIGNvdW50RWwudGV4dENvbnRlbnQgPSBjdXJyZW50TW92aWVzLmxlbmd0aCArICcgdGl0bGVzJzsKICB9IGNhdGNoIChlKSB7fQp9Cgphc3luYyBmdW5jdGlvbiBmZXRjaFR2KCkgewogIHRyeSB7CiAgICBjb25zdCBkYXRhID0gYXdhaXQgYXBpR2V0KCcvYXBpL3YxL2Rpc2NvdmVyL3R2Jyk7CiAgICBjdXJyZW50VHYgPSAoZGF0YSAmJiBkYXRhLnJlc3VsdHMpID8gZGF0YS5yZXN1bHRzIDogKEFycmF5LmlzQXJyYXkoZGF0YSkgPyBkYXRhIDogW10pOwogICAgcmVuZGVyR3JpZCgndHYtcHJldmlldy1ncmlkJywgY3VycmVudFR2LnNsaWNlKDAsIDgpKTsKICAgIGNvbnN0IGNvdW50RWwgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgndHYtZnVsbC1jb3VudCcpOwogICAgaWYgKGNvdW50RWwpIGNvdW50RWwudGV4dENvbnRlbnQgPSBjdXJyZW50VHYubGVuZ3RoICsgJyBzZXJpZXMnOwogIH0gY2F0Y2ggKGUpIHt9Cn0KCmFzeW5jIGZ1bmN0aW9uIGZldGNoTGlicmFyeSgpIHsKICB0cnkgewogICAgY29uc3QgZGF0YSA9IGF3YWl0IGFwaUdldCgnL2FwaS92MS9saWJyYXJ5Jyk7CiAgICBsaWJyYXJ5TW92aWVzID0gZGF0YS5tb3ZpZXMgfHwgW107CiAgICBsaWJyYXJ5U2VyaWVzID0gZGF0YS5zZXJpZXMgfHwgW107CiAgfSBjYXRjaCAoZSkge30KfQoKYXN5bmMgZnVuY3Rpb24gZmV0Y2hQcm9maWxlc0FuZEZvbGRlcnMoKSB7CiAgdHJ5IHsKICAgIGNvbnN0IFtwcm9maWxlcywgZm9sZGVyc10gPSBhd2FpdCBQcm9taXNlLmFsbChbCiAgICAgIGFwaUdldCgnL2FwaS92MS9xdWFsaXR5LXByb2ZpbGVzJykuY2F0Y2goKCkgPT4gKHt9KSksCiAgICAgIGFwaUdldCgnL2FwaS92MS9yb290LWZvbGRlcnMnKS5jYXRjaCgoKSA9PiAoe30pKQogICAgXSk7CiAgICByYWRhcnJRdWFsaXR5UHJvZmlsZXMgPSBwcm9maWxlcy5yYWRhcnIgfHwgW107CiAgICBzb25hcnJRdWFsaXR5UHJvZmlsZXMgPSBwcm9maWxlcy5zb25hcnIgfHwgW107CiAgICByYWRhcnJSb290Rm9sZGVycyA9IGZvbGRlcnMucmFkYXJyIHx8IFtdOwogICAgc29uYXJyUm9vdEZvbGRlcnMgPSBmb2xkZXJzLnNvbmFyciB8fCBbXTsKICB9IGNhdGNoIChlKSB7fQp9Cgphc3luYyBmdW5jdGlvbiBmZXRjaFJlcXVlc3RzTGlzdCgpIHsKICB0cnkgewogICAgY29uc3QgZGF0YSA9IGF3YWl0IGFwaUdldCgnL2FwaS92MS9yZXF1ZXN0Jyk7CiAgICBhY3RpdmVSZXF1ZXN0c0xpc3QgPSAoZGF0YSAmJiBkYXRhLnJlc3VsdHMpID8gZGF0YS5yZXN1bHRzIDogKEFycmF5LmlzQXJyYXkoZGF0YSkgPyBkYXRhIDogW10pOwogICAgY29uc3QgY291bnQgPSBhY3RpdmVSZXF1ZXN0c0xpc3QubGVuZ3RoOwogICAgY29uc3QgcmVxQmFkZ2UgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgncmVxLW5hdi1iYWRnZScpOwogICAgY29uc3QgbW9iQmFkZ2UgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9iaWxlLXJlcS1iYWRnZScpOwogICAgaWYgKHJlcUJhZGdlKSByZXFCYWRnZS50ZXh0Q29udGVudCA9IGNvdW50OwogICAgaWYgKG1vYkJhZGdlKSB7CiAgICAgIGlmIChjb3VudCA+IDApIG1vYkJhZGdlLmNsYXNzTGlzdC5yZW1vdmUoJ2hpZGRlbicpOwogICAgICBlbHNlIG1vYkJhZGdlLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwogICAgfQogICAgcmVuZGVyUmVxdWVzdHNMZWRnZXIoKTsKICB9IGNhdGNoIChlKSB7fQp9CgpmdW5jdGlvbiByZW5kZXJSZXF1ZXN0c0xlZGdlcigpIHsKICBjb25zdCBjb250YWluZXIgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgncmVxdWVzdHMtbGVkZ2VyLWxpc3QnKTsKICBpZiAoIWNvbnRhaW5lcikgcmV0dXJuOwogIGlmIChhY3RpdmVSZXF1ZXN0c0xpc3QubGVuZ3RoID09PSAwKSB7CiAgICBjb250YWluZXIuaW5uZXJIVE1MID0gJzxkaXYgY2xhc3M9InRleHQtY2VudGVyIHB5LTEyIHRleHQtZ3JheS01MDAgdGV4dC14cyI+Tm8gYWN0aXZlIHJlcXVlc3RzPC9kaXY+JzsKICAgIHJldHVybjsKICB9CgogIGNvbnN0IHBlbmRpbmdJdGVtcyA9IGFjdGl2ZVJlcXVlc3RzTGlzdC5maWx0ZXIociA9PiByLnN0YXR1cyA9PT0gJ3BlbmRpbmcnKTsKICBsZXQgaHRtbCA9ICcnOwoKICBpZiAocGVuZGluZ0l0ZW1zLmxlbmd0aCA+IDApIHsKICAgIGh0bWwgKz0gCiAgICAgICc8ZGl2IGNsYXNzPSJnbGFzcy1jYXJkIHJvdW5kZWQteGwgcC0zIGJvcmRlciBib3JkZXItYW1iZXItNTAwLzMwIGJnLWFtYmVyLTk1MC8yMCBmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4gZ2FwLTMgbWItMyI+JyArCiAgICAgICAgJzxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGdhcC0yLjUgbWluLXctMCI+JyArCiAgICAgICAgICAnPHNwYW4gY2xhc3M9InJlbGF0aXZlIGZsZXggaC0yLjUgdy0yLjUgZmxleC1zaHJpbmstMCI+JyArCiAgICAgICAgICAgICc8c3BhbiBjbGFzcz0iYW5pbWF0ZS1waW5nIGFic29sdXRlIGlubGluZS1mbGV4IGgtZnVsbCB3LWZ1bGwgcm91bmRlZC1mdWxsIGJnLWFtYmVyLTQwMCBvcGFjaXR5LTc1Ij48L3NwYW4+JyArCiAgICAgICAgICAgICc8c3BhbiBjbGFzcz0icmVsYXRpdmUgaW5saW5lLWZsZXggcm91bmRlZC1mdWxsIGgtMi41IHctMi41IGJnLWFtYmVyLTUwMCI+PC9zcGFuPicgKwogICAgICAgICAgJzwvc3Bhbj4nICsKICAgICAgICAgICc8ZGl2IGNsYXNzPSJtaW4tdy0wIj4nICsKICAgICAgICAgICAgJzxoNCBjbGFzcz0idGV4dC14cyBmb250LWJvbGQgdGV4dC1hbWJlci0yMDAiPicgKyBwZW5kaW5nSXRlbXMubGVuZ3RoICsgJyBSZXF1ZXN0JyArIChwZW5kaW5nSXRlbXMubGVuZ3RoID4gMSA/ICdzJyA6ICcnKSArICcgUXVldWVkIE9mZmxpbmU8L2g0PicgKwogICAgICAgICAgICAnPHAgY2xhc3M9InRleHQtWzEwcHhdIHRleHQtYW1iZXItMzAwLzgwIHRydW5jYXRlIj5XaWxsIGF1dG8tZGlzcGF0Y2ggYXV0b21hdGljYWxseSBvbmNlIGF1dG9tYXRpb24gc3RhY2sgc3RhcnRzLjwvcD4nICsKICAgICAgICAgICc8L2Rpdj4nICsKICAgICAgICAnPC9kaXY+JyArCiAgICAgICAgJzxidXR0b24gb25jbGljaz0iZGlzcGF0Y2hQZW5kaW5nTm93KCkiIGNsYXNzPSJweC0zIHB5LTEuNSByb3VuZGVkLWxnIGJnLWFtYmVyLTYwMCBob3ZlcjpiZy1hbWJlci01MDAgdGV4dC13aGl0ZSBmb250LXNlbWlib2xkIHRleHQteHMgZmxleC1zaHJpbmstMCB0cmFuc2l0aW9uLWFsbCBhY3RpdmU6c2NhbGUtOTUgc2hhZG93Ij4nICsKICAgICAgICAgICdEaXNwYXRjaCBOb3cnICsKICAgICAgICAnPC9idXR0b24+JyArCiAgICAgICc8L2Rpdj4nOwogIH0KCiAgYWN0aXZlUmVxdWVzdHNMaXN0LmZvckVhY2gociA9PiB7CiAgICBjb25zdCBkYXRlU3RyID0gci5yZXF1ZXN0ZWRBdCA/IG5ldyBEYXRlKHIucmVxdWVzdGVkQXQpLnRvTG9jYWxlRGF0ZVN0cmluZygpIDogJ1JlY2VudCc7CiAgICBjb25zdCB0eXBlTGFiZWwgPSAoci50eXBlID09PSAndHYnID8gJ1RWJyA6ICdNT1ZJRScpOwogICAgY29uc3Qgc2VydmljZU5hbWUgPSAoci50eXBlID09PSAndHYnID8gJ1NPTkFSUicgOiAnUkFEQVJSJyk7CiAgICBjb25zdCBpc1BlbmRpbmcgPSByLnN0YXR1cyA9PT0gJ3BlbmRpbmcnOwogICAgY29uc3QgaXNJbkxpYiA9IHIuc3RhdHVzID09PSAnaW5fbGlicmFyeSc7CgogICAgbGV0IGJhZGdlSHRtbCA9ICcnOwogICAgbGV0IHN0YXR1c1RleHQgPSAnJzsKICAgIGlmIChpc1BlbmRpbmcpIHsKICAgICAgYmFkZ2VIdG1sID0gJzxzcGFuIGNsYXNzPSJ0ZXh0LVs5cHhdIHB4LTEuNSBweS0wLjUgcm91bmRlZCBmb250LW1vbm8gZm9udC1ib2xkIGJnLWFtYmVyLTUwMC8yMCB0ZXh0LWFtYmVyLTMwMCBib3JkZXIgYm9yZGVyLWFtYmVyLTUwMC80MCI+UEVORElORyBPRkZMSU5FPC9zcGFuPic7CiAgICAgIHN0YXR1c1RleHQgPSAnPHAgY2xhc3M9InRleHQtWzEwcHhdIHRleHQtYW1iZXItMzAwLzgwIG10LTAuNSI+V2FpdGluZyBmb3IgJyArIHNlcnZpY2VOYW1lICsgJyB0byBzdGFydCDigKIgQXV0by1kaXNwYXRjaGVzIG9uIGxhdW5jaDwvcD4nOwogICAgfSBlbHNlIGlmIChpc0luTGliKSB7CiAgICAgIGJhZGdlSHRtbCA9ICc8c3BhbiBjbGFzcz0idGV4dC1bOXB4XSBweC0xLjUgcHktMC41IHJvdW5kZWQgZm9udC1tb25vIGZvbnQtYm9sZCBiZy1za3ktNTAwLzIwIHRleHQtc2t5LTMwMCBib3JkZXIgYm9yZGVyLXNreS01MDAvNDAiPklOIExJQlJBUlk8L3NwYW4+JzsKICAgICAgc3RhdHVzVGV4dCA9ICc8cCBjbGFzcz0idGV4dC1bMTBweF0gdGV4dC1za3ktMzAwLzgwIG10LTAuNSI+QXZhaWxhYmxlIGluIEplbGx5ZmluIGxpYnJhcnkg4oCiICcgKyBkYXRlU3RyICsgJzwvcD4nOwogICAgfSBlbHNlIHsKICAgICAgYmFkZ2VIdG1sID0gJzxzcGFuIGNsYXNzPSJ0ZXh0LVs5cHhdIHB4LTEuNSBweS0wLjUgcm91bmRlZCBmb250LW1vbm8gZm9udC1ib2xkIGJnLWVtZXJhbGQtNTAwLzIwIHRleHQtZW1lcmFsZC0zMDAgYm9yZGVyIGJvcmRlci1lbWVyYWxkLTUwMC80MCI+RElTUEFUQ0hFRDwvc3Bhbj4nOwogICAgICBzdGF0dXNUZXh0ID0gJzxwIGNsYXNzPSJ0ZXh0LVsxMHB4XSB0ZXh0LWdyYXktNDAwIG10LTAuNSI+JyArIHNlcnZpY2VOYW1lICsgJyDigKIgJyArIGRhdGVTdHIgKyAnPC9wPic7CiAgICB9CgogICAgaHRtbCArPSAKICAgICAgJzxkaXYgY2xhc3M9ImdsYXNzLWNhcmQgcm91bmRlZC14bCBwLTMgZmxleCBpdGVtcy1jZW50ZXIganVzdGlmeS1iZXR3ZWVuIGdhcC0zIGJvcmRlciAnICsgKGlzUGVuZGluZyA/ICdib3JkZXItYW1iZXItNTAwLzMwJyA6ICdib3JkZXItZ3JheS04MDAnKSArICciPicgKwogICAgICAgICc8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBnYXAtMi41IG1pbi13LTAiPicgKwogICAgICAgICAgJzxkaXYgY2xhc3M9InctMTAgaC0xNCByb3VuZGVkIG92ZXJmbG93LWhpZGRlbiBwb3N0ZXIgZmxleC1zaHJpbmstMCBib3JkZXIgYm9yZGVyLXdoaXRlLzUiPicgKwogICAgICAgICAgICAnPGltZyBzcmM9IicgKyBnZXRQb3N0ZXIocikgKyAnIiBhbHQ9IicgKyByLnRpdGxlICsgJyIgY2xhc3M9InctZnVsbCBoLWZ1bGwgb2JqZWN0LWNvdmVyIHBvaW50ZXItZXZlbnRzLW5vbmUiIG9uZXJyb3I9ImhhbmRsZUltZ0Vycm9yKHRoaXMsIFwnJyArIChyLnRpdGxlIHx8ICcnKS5yZXBsYWNlKC8nL2csICJcXCciKSArICdcJywgXCcnICsgKHIueWVhciB8fCAnJykgKyAnXCcpIiAvPicgKwogICAgICAgICAgJzwvZGl2PicgKwogICAgICAgICAgJzxkaXYgY2xhc3M9Im1pbi13LTAiPicgKwogICAgICAgICAgICAnPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTEuNSBtYi0wLjUiPicgKwogICAgICAgICAgICAgICc8c3BhbiBjbGFzcz0idGV4dC1bOXB4XSBweC0xIHB5LTAuMiByb3VuZGVkIGZvbnQtbW9ubyBmb250LWJvbGQgYmctYnJhbmQtNTAwLzIwIHRleHQtYnJhbmQtMzAwIj4nICsgdHlwZUxhYmVsICsgJzwvc3Bhbj4nICsKICAgICAgICAgICAgICAnPHNwYW4gY2xhc3M9InRleHQtWzExcHhdIHRleHQtZ3JheS00MDAgZm9udC1tb25vIj4nICsgKHIueWVhciB8fCAnJykgKyAnPC9zcGFuPicgKwogICAgICAgICAgICAgIGJhZGdlSHRtbCArCiAgICAgICAgICAgICc8L2Rpdj4nICsKICAgICAgICAgICAgJzxoNCBjbGFzcz0idGV4dC14cyBzbTp0ZXh0LXNtIGZvbnQtYm9sZCB0ZXh0LXdoaXRlIHRydW5jYXRlIj4nICsgKHIudGl0bGUgfHwgJ1VudGl0bGVkJykgKyAnPC9oND4nICsKICAgICAgICAgICAgc3RhdHVzVGV4dCArCiAgICAgICAgICAnPC9kaXY+JyArCiAgICAgICAgJzwvZGl2PicgKwogICAgICAgICc8YnV0dG9uIG9uY2xpY2s9ImNhbmNlbFJlcXVlc3RBY3Rpb24oJyArIHIuaWQgKyAnLCBcJycgKyAoci50aXRsZSB8fCAnJykucmVwbGFjZSgvJy9nLCAiXFwnIikgKyAnXCcsIFwnJyArIHIudHlwZSArICdcJywgJyArIChyLnRtZGJJZCB8fCAwKSArICcsICcgKyAoci50dmRiSWQgfHwgMCkgKyAnKSIgY2xhc3M9InB4LTIuNSBweS0xLjUgcm91bmRlZC1sZyBiZy1yb3NlLTk1MC83MCBob3ZlcjpiZy1yb3NlLTkwMCBib3JkZXIgYm9yZGVyLXJvc2UtNTAwLzQwIHRleHQtcm9zZS0zMDAgdGV4dC1bMTFweF0gZm9udC1zZW1pYm9sZCBmbGV4LXNocmluay0wIHRyYW5zaXRpb24tYWxsIGFjdGl2ZTpzY2FsZS05NSI+JyArCiAgICAgICAgICAoaXNQZW5kaW5nID8gJ1JlbW92ZScgOiAnQ2FuY2VsJykgKwogICAgICAgICc8L2J1dHRvbj4nICsKICAgICAgJzwvZGl2Pic7CiAgfSk7CiAgY29udGFpbmVyLmlubmVySFRNTCA9IGh0bWw7Cn0KCmFzeW5jIGZ1bmN0aW9uIGRpc3BhdGNoUGVuZGluZ05vdygpIHsKICBzaG93VG9hc3QoJ0NoZWNraW5nIHNlcnZpY2VzICYgZGlzcGF0Y2hpbmcgcGVuZGluZyByZXF1ZXN0cy4uLicsICdpbmZvJyk7CiAgdHJ5IHsKICAgIGNvbnN0IHJlcyA9IGF3YWl0IGFwaVBvc3QoJy9hcGkvdjEvcmVxdWVzdC9kaXNwYXRjaC1wZW5kaW5nJywge30pOwogICAgaWYgKHJlcyAmJiByZXMuZGlzcGF0Y2hlZCA+IDApIHsKICAgICAgc2hvd1RvYXN0KCdTdWNjZXNzZnVsbHkgZGlzcGF0Y2hlZCAnICsgcmVzLmRpc3BhdGNoZWQgKyAnIHBlbmRpbmcgcmVxdWVzdChzKSEnLCAnc3VjY2VzcycpOwogICAgfSBlbHNlIGlmIChyZXMgJiYgcmVzLnBlbmRpbmcgPiAwKSB7CiAgICAgIHNob3dUb2FzdCgnVGFyZ2V0IHNlcnZpY2UgaXMgc3RpbGwgb2ZmbGluZS4gJyArIHJlcy5wZW5kaW5nICsgJyByZXF1ZXN0KHMpIHJlbWFpbiBxdWV1ZWQuJywgJ3dhcm5pbmcnKTsKICAgIH0gZWxzZSB7CiAgICAgIHNob3dUb2FzdCgnTm8gcGVuZGluZyByZXF1ZXN0cyB0byBkaXNwYXRjaC4nLCAnaW5mbycpOwogICAgfQogICAgYXdhaXQgZmV0Y2hSZXF1ZXN0c0xpc3QoKTsKICAgIGF3YWl0IGZldGNoTGlicmFyeSgpOwogIH0gY2F0Y2ggKGUpIHsKICAgIHNob3dUb2FzdCgnRGlzcGF0Y2ggZXJyb3I6ICcgKyAoZS5tZXNzYWdlIHx8ICdTZXJ2aWNlIG9mZmxpbmUnKSwgJ2Vycm9yJyk7CiAgfQp9Cgphc3luYyBmdW5jdGlvbiBjYW5jZWxSZXF1ZXN0QWN0aW9uKGlkLCB0aXRsZSwgdHlwZSwgdG1kYklkLCB0dmRiSWQpIHsKICBpZiAoIWNvbmZpcm0oJ0NhbmNlbCByZXF1ZXN0IGZvciAiJyArIHRpdGxlICsgJyIgYW5kIHVubGluayBmcm9tIGF1dG9tYXRpb24/JykpIHJldHVybjsKICBzaG93VG9hc3QoJ0NhbmNlbGxpbmcgcmVxdWVzdCBmb3IgIicgKyB0aXRsZSArICciLi4uJywgJ2luZm8nKTsKICB0cnkgewogICAgY29uc3QgcmVzID0gYXdhaXQgYXBpUG9zdCgnL2FwaS92MS9yZXF1ZXN0L2NhbmNlbCcsIHsKICAgICAgaWQ6IGlkLAogICAgICB0aXRsZTogdGl0bGUsCiAgICAgIHR5cGU6IHR5cGUsCiAgICAgIHRtZGJJZDogdG1kYklkLAogICAgICB0dmRiSWQ6IHR2ZGJJZAogICAgfSk7CiAgICBzaG93VG9hc3QocmVzLm1lc3NhZ2UgfHwgKCdDYW5jZWxsZWQ6ICcgKyB0aXRsZSksICdzdWNjZXNzJyk7CiAgICBhd2FpdCBmZXRjaFJlcXVlc3RzTGlzdCgpOwogICAgYXdhaXQgZmV0Y2hMaWJyYXJ5KCk7CiAgICBpZiAoYWN0aXZlTW9kYWxJdGVtICYmIGdldEl0ZW1UaXRsZShhY3RpdmVNb2RhbEl0ZW0pID09PSB0aXRsZSkgewogICAgICBvcGVuSW5zdGFudE1vZGFsKGFjdGl2ZU1vZGFsSXRlbSk7CiAgICB9CiAgICBpZiAoaGVyb0l0ZW0gJiYgZ2V0SXRlbVRpdGxlKGhlcm9JdGVtKSA9PT0gdGl0bGUpIHsKICAgICAgcmVuZGVySGVybyhoZXJvSXRlbSk7CiAgICB9CiAgICBpZiAoY3VycmVudFRyZW5kaW5nLmxlbmd0aCA+IDApIHJlbmRlckdyaWQoJ3RyZW5kaW5nLWdyaWQnLCBjdXJyZW50VHJlbmRpbmcuc2xpY2UoMCwgMTYpKTsKICB9IGNhdGNoIChlcnIpIHsKICAgIHNob3dUb2FzdCgnRmFpbGVkIHRvIGNhbmNlbDogJyArIGVyci5tZXNzYWdlLCAnZXJyb3InKTsKICB9Cn0KCi8vIFNlcnZpY2VzCmFzeW5jIGZ1bmN0aW9uIGZldGNoU2VydmljZXNMaXN0KCkgewogIHRyeSB7CiAgICBjb25zdCBkYXRhID0gYXdhaXQgYXBpR2V0KCcvYXBpL3YxL3NlcnZpY2VzJyk7CiAgICBjb25zdCBzZXJ2aWNlcyA9IGRhdGEuc2VydmljZXMgfHwgW107CiAgICByZW5kZXJTZXJ2aWNlc0Rhc2hib2FyZChzZXJ2aWNlcyk7CiAgICBjb25zdCBhbnlPbmxpbmUgPSBzZXJ2aWNlcy5zb21lKHMgPT4gcy5vbmxpbmUpOwogICAgY29uc3QgbmF2RG90ID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ3NlcnZpY2VzLW5hdi1iYWRnZScpOwogICAgaWYgKG5hdkRvdCkgbmF2RG90LmNsYXNzTmFtZSA9ICd3LTIgaC0yIHJvdW5kZWQtZnVsbCAnICsgKGFueU9ubGluZSA/ICdiZy1lbWVyYWxkLTQwMCcgOiAnYmctZ3JheS01MDAnKTsKICB9IGNhdGNoIChlKSB7fQp9CgpmdW5jdGlvbiByZW5kZXJTZXJ2aWNlc0Rhc2hib2FyZChzZXJ2aWNlcykgewogIGNvbnN0IGdyaWQgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnc2VydmljZXMtZ3JpZCcpOwogIGlmICghZ3JpZCkgcmV0dXJuOwogIGxldCBodG1sID0gJyc7CiAgc2VydmljZXMuZm9yRWFjaChzID0+IHsKICAgIGNvbnN0IGlzT25saW5lID0gcy5vbmxpbmU7CiAgICBodG1sICs9IAogICAgICAnPGRpdiBjbGFzcz0iZ2xhc3MtY2FyZCByb3VuZGVkLXhsIHAtMyBmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4gZ2FwLTIuNSBib3JkZXIgYm9yZGVyLWdyYXktODAwIj4nICsKICAgICAgICAnPGRpdiBjbGFzcz0iZmxleCBpdGVtcy1jZW50ZXIgZ2FwLTIuNSI+JyArCiAgICAgICAgICAnPGRpdiBjbGFzcz0idy0yLjUgaC0yLjUgcm91bmRlZC1mdWxsICcgKyAoaXNPbmxpbmUgPyAnYmctZW1lcmFsZC00MDAgYW5pbWF0ZS1wdWxzZScgOiAnYmctZ3JheS02MDAnKSArICciPjwvZGl2PicgKwogICAgICAgICAgJzxkaXY+JyArCiAgICAgICAgICAgICc8aDQgY2xhc3M9InRleHQteHMgc206dGV4dC1zbSBmb250LWJvbGQgdGV4dC13aGl0ZSI+JyArIHMubmFtZSArICc8L2g0PicgKwogICAgICAgICAgICAnPHAgY2xhc3M9InRleHQtWzEwcHhdIHRleHQtZ3JheS00MDAiPicgKyBzLmNhdGVnb3J5ICsgJyDigKIgUG9ydCAnICsgcy5wb3J0ICsgJzwvcD4nICsKICAgICAgICAgICc8L2Rpdj4nICsKICAgICAgICAnPC9kaXY+JyArCiAgICAgICAgJzxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGdhcC0yIj4nICsKICAgICAgICAgICc8c3BhbiBjbGFzcz0idGV4dC1bMTFweF0gZm9udC1tb25vICcgKyAoaXNPbmxpbmUgPyAndGV4dC1lbWVyYWxkLTQwMCcgOiAndGV4dC1ncmF5LTUwMCcpICsgJyI+JyArIChpc09ubGluZSA/ICdPbmxpbmUnIDogJ1N0b3BwZWQnKSArICc8L3NwYW4+JyArCiAgICAgICAgICAnPGEgaHJlZj0iJyArIHMudXJsICsgJyIgdGFyZ2V0PSJfYmxhbmsiIGNsYXNzPSJweC0yLjUgcHktMSByb3VuZGVkLWxnIGJvcmRlciBib3JkZXItZ3JheS04MDAgdGV4dC1bMTFweF0gdGV4dC1ncmF5LTIwMCBob3Zlcjp0ZXh0LXdoaXRlIGZvbnQtbWVkaXVtIj5PcGVuPC9hPicgKwogICAgICAgICc8L2Rpdj4nICsKICAgICAgJzwvZGl2Pic7CiAgfSk7CiAgZ3JpZC5pbm5lckhUTUwgPSBodG1sOwp9CgovLyBIZXJvIFJlbmRlcmluZwpmdW5jdGlvbiByZW5kZXJIZXJvKGl0ZW0pIHsKICBoZXJvSXRlbSA9IGl0ZW07CiAgY29uc3QgYmcgPSBnZXRCYWNrZHJvcChpdGVtKTsKICBjb25zdCBwb3N0ZXIgPSBnZXRQb3N0ZXIoaXRlbSk7CiAgY29uc3QgdGl0bGUgPSBnZXRJdGVtVGl0bGUoaXRlbSk7CiAgY29uc3QgeWVhciA9IGdldEl0ZW1ZZWFyKGl0ZW0pOwogIGNvbnN0IHR5cGUgPSBnZXRJdGVtVHlwZShpdGVtKTsKCiAgY29uc3QgaGVyb0JnID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2hlcm8tYmcnKTsKICBjb25zdCBoZXJvUG9zdGVyID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2hlcm8tcG9zdGVyJyk7CiAgaWYgKGhlcm9CZykgewogICAgaWYgKGJnKSBoZXJvQmcuc3R5bGUuYmFja2dyb3VuZEltYWdlID0gJ3VybCgnICsgYmcgKyAnKSc7CiAgICBlbHNlIGhlcm9CZy5zdHlsZS5iYWNrZ3JvdW5kSW1hZ2UgPSAnbGluZWFyLWdyYWRpZW50KDEzNWRlZywgIzFlMWI0YiAwJSwgIzA2MDkxMyAxMDAlKSc7CiAgfQogIGlmIChoZXJvUG9zdGVyKSB7CiAgICBoZXJvUG9zdGVyLnNyYyA9IHBvc3RlcjsKICAgIGhlcm9Qb3N0ZXIub25lcnJvciA9ICgpID0+IGhhbmRsZUltZ0Vycm9yKGhlcm9Qb3N0ZXIsIHRpdGxlLCB5ZWFyKTsKICB9CgogIGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdoZXJvLXRpdGxlJykudGV4dENvbnRlbnQgPSB0aXRsZTsKICBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnaGVyby1vdmVydmlldycpLnRleHRDb250ZW50ID0gaXRlbS5vdmVydmlldyB8fCAnTm8gc3lub3BzaXMgcHJvdmlkZWQuJzsKICBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnaGVyby1yYXRpbmcnKS50ZXh0Q29udGVudCA9IChpdGVtLnZvdGVBdmVyYWdlIHx8IGl0ZW0ucmF0aW5nIHx8IDApLnRvRml4ZWQoMSk7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2hlcm8tdHlwZS1iYWRnZScpLnRleHRDb250ZW50ID0gdHlwZSA9PT0gJ3R2JyA/ICdUViBTSE9XJyA6ICdNT1ZJRSc7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2hlcm8tbWV0YScpLnRleHRDb250ZW50ID0geWVhciArIChpdGVtLmNlcnRpZmljYXRpb24gPyAnIOKAoiAnICsgaXRlbS5jZXJ0aWZpY2F0aW9uIDogJycpICsgKGl0ZW0ucnVudGltZSA/ICcg4oCiICcgKyBpdGVtLnJ1bnRpbWUgOiAnJyk7CgogIGNvbnN0IGdlbnJlcyA9IGl0ZW0uZ2VucmVzIHx8IFtdOwogIGNvbnN0IGdlbnJlTmFtZXMgPSBnZW5yZXMubWFwKGcgPT4gKHR5cGVvZiBnID09PSAnc3RyaW5nJyA/IGcgOiBnLm5hbWUpKS5zbGljZSgwLCAyKTsKICBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnaGVyby1nZW5yZXMnKS50ZXh0Q29udGVudCA9IGdlbnJlTmFtZXMuam9pbignIOKAoiAnKTsKCiAgY29uc3QgbGliU3RhdHVzID0gZ2V0SXRlbUxpYnJhcnlTdGF0dXMoaXRlbSk7CiAgY29uc3QgaGVyb1N0YXR1cyA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdoZXJvLWxpYnJhcnktc3RhdHVzJyk7CiAgY29uc3QgYWN0aW9uVGV4dCA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdoZXJvLWFjdGlvbi10ZXh0Jyk7CiAgY29uc3QgYWN0aW9uQnRuID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2hlcm8tYWN0aW9uLWJ0bicpOwoKICBpZiAoaGVyb1N0YXR1cykgewogICAgaWYgKGxpYlN0YXR1cykgewogICAgICBoZXJvU3RhdHVzLmNsYXNzTmFtZSA9ICd0ZXh0LVsxMHB4XSBweC0xLjUgcHktMC4yIHJvdW5kZWQgZm9udC1tZWRpdW0gJyArIGxpYlN0YXR1cy5jc3NDbGFzczsKICAgICAgaGVyb1N0YXR1cy50ZXh0Q29udGVudCA9IGxpYlN0YXR1cy5sYWJlbDsKICAgICAgaGVyb1N0YXR1cy5jbGFzc0xpc3QucmVtb3ZlKCdoaWRkZW4nKTsKICAgIH0gZWxzZSB7CiAgICAgIGhlcm9TdGF0dXMuY2xhc3NMaXN0LmFkZCgnaGlkZGVuJyk7CiAgICB9CiAgfQoKICBpZiAoYWN0aW9uVGV4dCAmJiBhY3Rpb25CdG4pIHsKICAgIGlmIChsaWJTdGF0dXMgJiYgbGliU3RhdHVzLmlzUmVxdWVzdGVkKSB7CiAgICAgIGFjdGlvblRleHQudGV4dENvbnRlbnQgPSAnQ2FuY2VsIFJlcXVlc3QnOwogICAgICBhY3Rpb25CdG4uY2xhc3NOYW1lID0gJ3B4LTMuNSBweS0xLjUgcm91bmRlZC1sZyBiZy1yb3NlLTk1MC84MCBob3ZlcjpiZy1yb3NlLTkwMCBib3JkZXIgYm9yZGVyLXJvc2UtNTAwLzQwIHRleHQtcm9zZS0zMDAgZm9udC1zZW1pYm9sZCB0ZXh0LXhzIHRyYW5zaXRpb24tYWxsIGFjdGl2ZTpzY2FsZS05NSBzaGFkb3cnOwogICAgICBhY3Rpb25CdG4ub25jbGljayA9ICgpID0+IGNhbmNlbFJlcXVlc3RBY3Rpb24obGliU3RhdHVzLnJlcXVlc3RJZCwgdGl0bGUsIHR5cGUsIGl0ZW0udG1kYklkLCBpdGVtLnR2ZGJJZCk7CiAgICB9IGVsc2UgaWYgKGxpYlN0YXR1cyAmJiBsaWJTdGF0dXMuaXNBdmFpbGFibGUpIHsKICAgICAgYWN0aW9uVGV4dC50ZXh0Q29udGVudCA9ICdJbiBMaWJyYXJ5JzsKICAgICAgYWN0aW9uQnRuLmNsYXNzTmFtZSA9ICdweC0zLjUgcHktMS41IHJvdW5kZWQtbGcgYmctZW1lcmFsZC05NTAvODAgYm9yZGVyIGJvcmRlci1lbWVyYWxkLTUwMC80MCB0ZXh0LWVtZXJhbGQtMzAwIGZvbnQtc2VtaWJvbGQgdGV4dC14cyBzaGFkb3cnOwogICAgICBhY3Rpb25CdG4ub25jbGljayA9ICgpID0+IHNob3dUb2FzdCgnVGl0bGUgYWxyZWFkeSBpbmRleGVkIGFuZCByZWFkeSBpbiBKZWxseWZpbicsICdpbmZvJyk7CiAgICB9IGVsc2UgewogICAgICBhY3Rpb25UZXh0LnRleHRDb250ZW50ID0gJ1JlcXVlc3QgJyArICh0eXBlID09PSAndHYnID8gJ1NlcmllcycgOiAnTW92aWUnKTsKICAgICAgYWN0aW9uQnRuLmNsYXNzTmFtZSA9ICdweC0zLjUgcHktMS41IHJvdW5kZWQtbGcgYmctYnJhbmQtNjAwIGhvdmVyOmJnLWJyYW5kLTUwMCB0ZXh0LXdoaXRlIGZvbnQtc2VtaWJvbGQgdGV4dC14cyB0cmFuc2l0aW9uLWFsbCBhY3RpdmU6c2NhbGUtOTUgc2hhZG93JzsKICAgICAgYWN0aW9uQnRuLm9uY2xpY2sgPSAoKSA9PiBvcGVuSW5zdGFudE1vZGFsKGl0ZW0pOwogICAgfQogIH0KfQoKZnVuY3Rpb24gb3Blbkhlcm9Nb2RhbCgpIHsKICBpZiAoaGVyb0l0ZW0pIG9wZW5JbnN0YW50TW9kYWwoaGVyb0l0ZW0pOwp9CgovLyBFWEFDVCA0LUNPTFVNTiBSRVNQT05TSVZFIEdSSUQgKE1vYmlsZTogNCwgVGFibGV0L1BDOiBNYXggZml0KQpmdW5jdGlvbiByZW5kZXJHcmlkKGNvbnRhaW5lcklkLCBpdGVtcykgewogIGNvbnN0IGdyaWQgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZChjb250YWluZXJJZCk7CiAgaWYgKCFncmlkKSByZXR1cm47CiAgZ3JpZC5pbm5lckhUTUwgPSAnJzsKICBpZiAoIWl0ZW1zIHx8IGl0ZW1zLmxlbmd0aCA9PT0gMCkgewogICAgZ3JpZC5pbm5lckhUTUwgPSAnPGRpdiBjbGFzcz0iY29sLXNwYW4tZnVsbCBweS02IHRleHQtY2VudGVyIHRleHQtZ3JheS01MDAgdGV4dC14cyI+Tm8gbWVkaWEgZm91bmQ8L2Rpdj4nOwogICAgcmV0dXJuOwogIH0KCiAgaXRlbXMuZm9yRWFjaChpdGVtID0+IHsKICAgIGdyaWQuYXBwZW5kQ2hpbGQoY3JlYXRlTWVkaWFDYXJkKGl0ZW0pKTsKICB9KTsKfQoKZnVuY3Rpb24gY3JlYXRlTWVkaWFDYXJkKGl0ZW0pIHsKICBjb25zdCB0aXRsZSA9IGdldEl0ZW1UaXRsZShpdGVtKTsKICBjb25zdCB5ZWFyID0gZ2V0SXRlbVllYXIoaXRlbSk7CiAgY29uc3QgdHlwZSA9IGdldEl0ZW1UeXBlKGl0ZW0pOwogIGNvbnN0IHJhdGluZyA9IChpdGVtLnZvdGVBdmVyYWdlIHx8IGl0ZW0ucmF0aW5nIHx8IDApLnRvRml4ZWQoMSk7CiAgY29uc3QgcG9zdGVyID0gZ2V0UG9zdGVyKGl0ZW0pOwogIGNvbnN0IGxpYlN0YXR1cyA9IGdldEl0ZW1MaWJyYXJ5U3RhdHVzKGl0ZW0pOwoKICAvLyBCdXR0b24gd3JhcHBlciBmb3IgMTAwJSByZWxpYWJsZSB0b3VjaCAmIGNsaWNrIGhhbmRsaW5nIHdpdGhvdXQgZHJhZyBpc3N1ZXMKICBjb25zdCBjYXJkID0gZG9jdW1lbnQuY3JlYXRlRWxlbWVudCgnYnV0dG9uJyk7CiAgY2FyZC50eXBlID0gJ2J1dHRvbic7CiAgY2FyZC5jbGFzc05hbWUgPSAnZ3JvdXAgdGV4dC1sZWZ0IHAtMCBiZy10cmFuc3BhcmVudCBib3JkZXItMCBmb2N1czpvdXRsaW5lLW5vbmUgc2VsZWN0LW5vbmUgdy1mdWxsIGN1cnNvci1wb2ludGVyJzsKICBjYXJkLm9uY2xpY2sgPSAoZSkgPT4gewogICAgZS5zdG9wUHJvcGFnYXRpb24oKTsKICAgIG9wZW5JbnN0YW50TW9kYWwoaXRlbSk7CiAgfTsKCiAgbGV0IHN0YXR1c1BpbGxIdG1sID0gJyc7CiAgaWYgKGxpYlN0YXR1cykgewogICAgc3RhdHVzUGlsbEh0bWwgPSAnPGRpdiBjbGFzcz0iYWJzb2x1dGUgdG9wLTEgcmlnaHQtMSB6LTEwIj48c3BhbiBjbGFzcz0iYmFkZ2UgdGV4dC1bOHB4XSBweC0xIHB5LTAuMiByb3VuZGVkIGZvbnQtYm9sZCAnICsgbGliU3RhdHVzLmNzc0NsYXNzICsgJyI+JyArIGxpYlN0YXR1cy5sYWJlbCArICc8L3NwYW4+PC9kaXY+JzsKICB9CgogIGNhcmQuaW5uZXJIVE1MID0gCiAgICAnPGRpdiBjbGFzcz0icG9zdGVyIGFzcGVjdC1bMi8zXSByb3VuZGVkLWxnIG92ZXJmbG93LWhpZGRlbiBtYi0xIHJlbGF0aXZlIGJvcmRlciBib3JkZXItd2hpdGUvNSBzaGFkb3ctc20iPicgKwogICAgICAnPGltZyBzcmM9IicgKyBwb3N0ZXIgKyAnIiBhbHQ9IicgKyB0aXRsZS5yZXBsYWNlKC8iL2csICcmcXVvdDsnKSArICciIGNsYXNzPSJ3LWZ1bGwgaC1mdWxsIG9iamVjdC1jb3ZlciBwb2ludGVyLWV2ZW50cy1ub25lIHNlbGVjdC1ub25lIiBsb2FkaW5nPSJsYXp5IiBvbmVycm9yPSJoYW5kbGVJbWdFcnJvcih0aGlzLCBcJycgKyB0aXRsZS5yZXBsYWNlKC8nL2csICJcXCciKSArICdcJywgXCcnICsgeWVhciArICdcJykiIC8+JyArCiAgICAgIHN0YXR1c1BpbGxIdG1sICsKICAgICAgJzxkaXYgY2xhc3M9ImFic29sdXRlIGJvdHRvbS0xIGxlZnQtMSByaWdodC0xIHotMTAgZmxleCBpdGVtcy1jZW50ZXIganVzdGlmeS1iZXR3ZWVuIHBvaW50ZXItZXZlbnRzLW5vbmUiPicgKwogICAgICAgICc8c3BhbiBjbGFzcz0iYmFkZ2UgdGV4dC1bOXB4XSBweC0xIHB5LTAuMiByb3VuZGVkIGZsZXggaXRlbXMtY2VudGVyIGdhcC0wLjUgZm9udC1ib2xkIHRleHQtd2hpdGUiPicgKwogICAgICAgICAgJzxzdmcgY2xhc3M9InctMi41IGgtMi41IHRleHQtYW1iZXItNDAwIiBmaWxsPSJjdXJyZW50Q29sb3IiIHZpZXdCb3g9IjAgMCAyMCAyMCI+PHBhdGggZD0iTTkuMDQ5IDIuOTI3Yy4zLS45MjEgMS42MDMtLjkyMSAxLjkwMiAwbDEuMDcgMy4yOTJhMSAxIDAgMDAuOTUuNjloMy40NjJjLjk2OSAwIDEuMzcxIDEuMjQuNTg4IDEuODFsLTIuOCAyLjAzNGExIDEgMCAwMC0uMzY0IDEuMTE4bDEuMDcgMy4yOTJjLjMuOTIxLS43NTUgMS42ODgtMS41NCAxLjExOGwtMi44LTIuMDM0YTEgMSAwIDAwLTEuMTc1IDBsLTIuOCAyLjAzNGMtLjc4NC41Ny0xLjgzOC0uMTk3LTEuNTM5LTEuMTE4bDEuMDctMy4yOTJhMSAxIDAgMDAtLjM2NC0xLjExOEwyLjk4IDguNzJjLS43ODMtLjU3LS4zOC0xLjgxLjU4OC0xLjgxaDMuNDYxYTEgMSAwIDAwLjk1MS0uNjlsMS4wNy0zLjI5MnoiLz48L3N2Zz4nICsKICAgICAgICAgIHJhdGluZyArCiAgICAgICAgJzwvc3Bhbj4nICsKICAgICAgICAnPHNwYW4gY2xhc3M9ImJhZGdlIHRleHQtWzhweF0gcHgtMSBweS0wLjIgcm91bmRlZCBmb250LW1vbm8gZm9udC1ib2xkIHRleHQtZ3JheS0zMDAiPicgKyAodHlwZSA9PT0gJ3R2JyA/ICdUVicgOiAnTU9WJykgKyAnPC9zcGFuPicgKwogICAgICAnPC9kaXY+JyArCiAgICAnPC9kaXY+JyArCiAgICAnPHAgY2xhc3M9InRleHQtWzExcHhdIGZvbnQtc2VtaWJvbGQgdHJ1bmNhdGUgdGV4dC1ncmF5LTIwMCBncm91cC1ob3Zlcjp0ZXh0LXdoaXRlIGxlYWRpbmctdGlnaHQiPicgKyB0aXRsZSArICc8L3A+JyArCiAgICAnPHAgY2xhc3M9InRleHQtWzEwcHhdIHRleHQtZ3JheS01MDAgZm9udC1tb25vIj4nICsgeWVhciArICc8L3A+JzsKCiAgcmV0dXJuIGNhcmQ7Cn0KCi8vIDEtVEFQIElOU1RBTlQgQUNUSU9OIE1PREFMIChOTyBTQ1JPTExJTkcgRE9XTiEpCmZ1bmN0aW9uIG9wZW5JbnN0YW50TW9kYWwoaXRlbSkgewogIGlmICghaXRlbSkgcmV0dXJuOwogIGFjdGl2ZU1vZGFsSXRlbSA9IGl0ZW07CgogIGNvbnN0IG1vZGFsID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2RldGFpbHMtbW9kYWwnKTsKICBjb25zdCBiZyA9IGdldEJhY2tkcm9wKGl0ZW0pOwogIGNvbnN0IHBvc3RlciA9IGdldFBvc3RlcihpdGVtKTsKICBjb25zdCB0aXRsZSA9IGdldEl0ZW1UaXRsZShpdGVtKTsKICBjb25zdCB5ZWFyID0gZ2V0SXRlbVllYXIoaXRlbSk7CiAgY29uc3QgdHlwZSA9IGdldEl0ZW1UeXBlKGl0ZW0pOwoKICBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtYmFja2Ryb3AnKS5zdHlsZS5iYWNrZ3JvdW5kSW1hZ2UgPSBiZyA/ICgndXJsKCcgKyBiZyArICcpJykgOiAnbm9uZSc7CiAgY29uc3QgbVBvc3RlciA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdtb2RhbC1wb3N0ZXInKTsKICBtUG9zdGVyLnNyYyA9IHBvc3RlcjsKICBtUG9zdGVyLm9uZXJyb3IgPSAoKSA9PiBoYW5kbGVJbWdFcnJvcihtUG9zdGVyLCB0aXRsZSwgeWVhcik7CgogIGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdtb2RhbC10aXRsZScpLnRleHRDb250ZW50ID0gdGl0bGU7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXllYXInKS50ZXh0Q29udGVudCA9IHllYXI7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXR5cGUnKS50ZXh0Q29udGVudCA9IHR5cGUgPT09ICd0dicgPyAnVFYgU0hPVycgOiAnTU9WSUUnOwogIGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdtb2RhbC1yYXRpbmcnKS50ZXh0Q29udGVudCA9IChpdGVtLnZvdGVBdmVyYWdlIHx8IGl0ZW0ucmF0aW5nIHx8IDApLnRvRml4ZWQoMSk7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXJ1bnRpbWUnKS50ZXh0Q29udGVudCA9IGl0ZW0ucnVudGltZSA/IChpdGVtLnJ1bnRpbWUgKyAoU3RyaW5nKGl0ZW0ucnVudGltZSkuaW5jbHVkZXMoJ20nKSA/ICcnIDogJyBtaW5zJykpIDogJyc7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLW92ZXJ2aWV3JykudGV4dENvbnRlbnQgPSBpdGVtLm92ZXJ2aWV3IHx8ICdObyBzeW5vcHNpcyBwcm92aWRlZC4nOwoKICAvLyBRdWFsaXR5IFByb2ZpbGVzCiAgY29uc3QgcVNlbGVjdCA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdtb2RhbC1xdWFsaXR5Jyk7CiAgcVNlbGVjdC5pbm5lckhUTUwgPSAnJzsKICBjb25zdCBwcm9maWxlcyA9ICh0eXBlID09PSAndHYnID8gc29uYXJyUXVhbGl0eVByb2ZpbGVzIDogcmFkYXJyUXVhbGl0eVByb2ZpbGVzKTsKICBpZiAocHJvZmlsZXMubGVuZ3RoID4gMCkgewogICAgcHJvZmlsZXMuZm9yRWFjaChwID0+IHsKICAgICAgY29uc3Qgb3B0ID0gZG9jdW1lbnQuY3JlYXRlRWxlbWVudCgnb3B0aW9uJyk7CiAgICAgIG9wdC52YWx1ZSA9IHAuaWQ7CiAgICAgIG9wdC50ZXh0Q29udGVudCA9IHAubmFtZTsKICAgICAgaWYgKHAubmFtZS50b0xvd2VyQ2FzZSgpLmluY2x1ZGVzKCcxMDgwcCcpKSBvcHQuc2VsZWN0ZWQgPSB0cnVlOwogICAgICBxU2VsZWN0LmFwcGVuZENoaWxkKG9wdCk7CiAgICB9KTsKICB9IGVsc2UgewogICAgcVNlbGVjdC5pbm5lckhUTUwgPSAnPG9wdGlvbiB2YWx1ZT0iNCIgc2VsZWN0ZWQ+SEQtMTA4MHAgKE9wdGltYWwpPC9vcHRpb24+PG9wdGlvbiB2YWx1ZT0iNSI+VWx0cmEtSEQgKDRLKTwvb3B0aW9uPjxvcHRpb24gdmFsdWU9IjEiPkFueSBRdWFsaXR5PC9vcHRpb24+JzsKICB9CgogIC8vIFJvb3QgRm9sZGVycwogIGNvbnN0IHJmU2VsZWN0ID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXJvb3QnKTsKICByZlNlbGVjdC5pbm5lckhUTUwgPSAnJzsKICBjb25zdCBmb2xkZXJzID0gKHR5cGUgPT09ICd0dicgPyBzb25hcnJSb290Rm9sZGVycyA6IHJhZGFyclJvb3RGb2xkZXJzKTsKICBpZiAoZm9sZGVycy5sZW5ndGggPiAwKSB7CiAgICBmb2xkZXJzLmZvckVhY2goZiA9PiB7CiAgICAgIGNvbnN0IG9wdCA9IGRvY3VtZW50LmNyZWF0ZUVsZW1lbnQoJ29wdGlvbicpOwogICAgICBvcHQudmFsdWUgPSBmOwogICAgICBvcHQudGV4dENvbnRlbnQgPSBmOwogICAgICByZlNlbGVjdC5hcHBlbmRDaGlsZChvcHQpOwogICAgfSk7CiAgfSBlbHNlIHsKICAgIGNvbnN0IGRlZmF1bHRQYXRoID0gKHR5cGUgPT09ICd0dicgPyAnL3N0b3JhZ2UvZW11bGF0ZWQvMC9GaXNoYm93bE1lZGlhL1RWJyA6ICcvc3RvcmFnZS9lbXVsYXRlZC8wL0Zpc2hib3dsTWVkaWEvTW92aWVzJyk7CiAgICByZlNlbGVjdC5pbm5lckhUTUwgPSAnPG9wdGlvbiB2YWx1ZT0iJyArIGRlZmF1bHRQYXRoICsgJyI+JyArIGRlZmF1bHRQYXRoICsgJzwvb3B0aW9uPic7CiAgfQoKICAvLyBUViBTZWFzb25zIENoaXBzCiAgY29uc3QgdHZTZWN0aW9uID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXR2LXNlY3Rpb24nKTsKICBjb25zdCBzZWFzb25zR3JpZCA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdtb2RhbC1zZWFzb25zLWNoaXBzJyk7CiAgc2Vhc29uc0dyaWQuaW5uZXJIVE1MID0gJyc7CiAgbW9kYWxTZWxlY3RlZFNlYXNvbnMgPSBbXTsKCiAgaWYgKHR5cGUgPT09ICd0dicpIHsKICAgIHR2U2VjdGlvbi5jbGFzc0xpc3QucmVtb3ZlKCdoaWRkZW4nKTsKICAgIGNvbnN0IHNlYXNvbnNMaXN0ID0gKGl0ZW0uc2Vhc29ucyAmJiBpdGVtLnNlYXNvbnMubGVuZ3RoID4gMCkgPyBpdGVtLnNlYXNvbnMgOiBbeyBzZWFzb25OdW1iZXI6IDEsIGVwaXNvZGVDb3VudDogMTAgfV07CiAgICBzZWFzb25zTGlzdC5mb3JFYWNoKHMgPT4gewogICAgICBpZiAocy5zZWFzb25OdW1iZXIgPT09IDApIHJldHVybjsKICAgICAgbW9kYWxTZWxlY3RlZFNlYXNvbnMucHVzaChzLnNlYXNvbk51bWJlcik7CiAgICAgIGNvbnN0IGNoaXAgPSBkb2N1bWVudC5jcmVhdGVFbGVtZW50KCdkaXYnKTsKICAgICAgY2hpcC5jbGFzc05hbWUgPSAnc2Vhc29uLWNoaXAgc2VsZWN0ZWQnOwogICAgICBjaGlwLnRleHRDb250ZW50ID0gJ1MnICsgcy5zZWFzb25OdW1iZXIgKyAocy5lcGlzb2RlQ291bnQgPyAnICgnICsgcy5lcGlzb2RlQ291bnQgKyAnIGVwcyknIDogJycpOwogICAgICBjaGlwLm9uY2xpY2sgPSAoKSA9PiB7CiAgICAgICAgY2hpcC5jbGFzc0xpc3QudG9nZ2xlKCdzZWxlY3RlZCcpOwogICAgICAgIGNvbnN0IG51bSA9IHBhcnNlSW50KHMuc2Vhc29uTnVtYmVyLCAxMCk7CiAgICAgICAgaWYgKGNoaXAuY2xhc3NMaXN0LmNvbnRhaW5zKCdzZWxlY3RlZCcpKSB7CiAgICAgICAgICBpZiAoIW1vZGFsU2VsZWN0ZWRTZWFzb25zLmluY2x1ZGVzKG51bSkpIG1vZGFsU2VsZWN0ZWRTZWFzb25zLnB1c2gobnVtKTsKICAgICAgICB9IGVsc2UgewogICAgICAgICAgbW9kYWxTZWxlY3RlZFNlYXNvbnMgPSBtb2RhbFNlbGVjdGVkU2Vhc29ucy5maWx0ZXIoeCA9PiB4ICE9PSBudW0pOwogICAgICAgIH0KICAgICAgfTsKICAgICAgc2Vhc29uc0dyaWQuYXBwZW5kQ2hpbGQoY2hpcCk7CiAgICB9KTsKICB9IGVsc2UgewogICAgdHZTZWN0aW9uLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwogIH0KCiAgY29uc3QgbGliU3RhdHVzID0gZ2V0SXRlbUxpYnJhcnlTdGF0dXMoaXRlbSk7CiAgY29uc3Qgc3RhdHVzQmFkZ2UgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtc3RhdHVzLWJhZGdlJyk7CiAgY29uc3Qgc3VibWl0QnRuID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXN1Ym1pdC1idG4nKTsKICBjb25zdCBzdWJtaXRUZXh0ID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLXN1Ym1pdC10ZXh0Jyk7CiAgY29uc3QgY2FuY2VsQnRuID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ21vZGFsLWNhbmNlbC1idG4nKTsKCiAgaWYgKGxpYlN0YXR1cykgewogICAgc3RhdHVzQmFkZ2UuY2xhc3NOYW1lID0gJ3RleHQtWzlweF0gcHgtMS41IHB5LTAuMiByb3VuZGVkIGZvbnQtbWVkaXVtICcgKyBsaWJTdGF0dXMuY3NzQ2xhc3M7CiAgICBzdGF0dXNCYWRnZS50ZXh0Q29udGVudCA9IGxpYlN0YXR1cy5sYWJlbDsKICAgIHN0YXR1c0JhZGdlLmNsYXNzTGlzdC5yZW1vdmUoJ2hpZGRlbicpOwogIH0gZWxzZSB7CiAgICBzdGF0dXNCYWRnZS5jbGFzc0xpc3QuYWRkKCdoaWRkZW4nKTsKICB9CgogIGlmIChsaWJTdGF0dXMgJiYgbGliU3RhdHVzLmlzUmVxdWVzdGVkKSB7CiAgICBpZiAobGliU3RhdHVzLmlzUGVuZGluZykgewogICAgICBzdGF0dXNCYWRnZS5jbGFzc05hbWUgPSAndGV4dC1bOXB4XSBweC0xLjUgcHktMC41IHJvdW5kZWQgZm9udC1tZWRpdW0gJyArIGxpYlN0YXR1cy5jc3NDbGFzczsKICAgICAgc3RhdHVzQmFkZ2UudGV4dENvbnRlbnQgPSAnUGVuZGluZyBPZmZsaW5lJzsKICAgICAgc3RhdHVzQmFkZ2UuY2xhc3NMaXN0LnJlbW92ZSgnaGlkZGVuJyk7CiAgICAgIHN1Ym1pdEJ0bi5jbGFzc0xpc3QucmVtb3ZlKCdoaWRkZW4nKTsKICAgICAgY2FuY2VsQnRuLmNsYXNzTGlzdC5yZW1vdmUoJ2hpZGRlbicpOwogICAgICBzdWJtaXRCdG4uY2xhc3NOYW1lID0gJ3ctZnVsbCBweS0yLjUgcm91bmRlZC14bCBiZy1hbWJlci05NTAvNzAgYm9yZGVyIGJvcmRlci1hbWJlci01MDAvNTAgdGV4dC1hbWJlci0zMDAgZm9udC1zZW1pYm9sZCB0ZXh0LXhzIGZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktY2VudGVyIGdhcC0yIHNoYWRvdyc7CiAgICAgIHN1Ym1pdFRleHQudGV4dENvbnRlbnQgPSAn4o+xIFF1ZXVlZCBpbiBQZW5kaW5nIFJlcXVlc3RzJzsKICAgICAgc3VibWl0QnRuLm9uY2xpY2sgPSAoKSA9PiBzaG93VG9hc3QoJ1RoaXMgcmVxdWVzdCB3aWxsIGF1dG9tYXRpY2FsbHkgZGlzcGF0Y2ggd2hlbiAnICsgKHR5cGUgPT09ICd0dicgPyAnU29uYXJyJyA6ICdSYWRhcnInKSArICcgc3RhcnRzLicsICdpbmZvJyk7CiAgICAgIGNhbmNlbEJ0bi5vbmNsaWNrID0gKCkgPT4gY2FuY2VsUmVxdWVzdEFjdGlvbihsaWJTdGF0dXMucmVxdWVzdElkLCB0aXRsZSwgdHlwZSwgaXRlbS50bWRiSWQsIGl0ZW0udHZkYklkKTsKICAgIH0gZWxzZSB7CiAgICAgIHN0YXR1c0JhZGdlLmNsYXNzTmFtZSA9ICd0ZXh0LVs5cHhdIHB4LTEuNSBweS0wLjIgcm91bmRlZCBmb250LW1lZGl1bSAnICsgbGliU3RhdHVzLmNzc0NsYXNzOwogICAgICBzdGF0dXNCYWRnZS50ZXh0Q29udGVudCA9IGxpYlN0YXR1cy5sYWJlbDsKICAgICAgc3RhdHVzQmFkZ2UuY2xhc3NMaXN0LnJlbW92ZSgnaGlkZGVuJyk7CiAgICAgIHN1Ym1pdEJ0bi5jbGFzc0xpc3QuYWRkKCdoaWRkZW4nKTsKICAgICAgY2FuY2VsQnRuLmNsYXNzTGlzdC5yZW1vdmUoJ2hpZGRlbicpOwogICAgICBjYW5jZWxCdG4ub25jbGljayA9ICgpID0+IGNhbmNlbFJlcXVlc3RBY3Rpb24obGliU3RhdHVzLnJlcXVlc3RJZCwgdGl0bGUsIHR5cGUsIGl0ZW0udG1kYklkLCBpdGVtLnR2ZGJJZCk7CiAgICB9CiAgfSBlbHNlIGlmIChsaWJTdGF0dXMgJiYgbGliU3RhdHVzLmlzQXZhaWxhYmxlKSB7CiAgICBzdWJtaXRCdG4uY2xhc3NMaXN0LnJlbW92ZSgnaGlkZGVuJyk7CiAgICBjYW5jZWxCdG4uY2xhc3NMaXN0LmFkZCgnaGlkZGVuJyk7CiAgICBzdWJtaXRCdG4uY2xhc3NOYW1lID0gJ3ctZnVsbCBweS0yLjUgcm91bmRlZC14bCBiZy1lbWVyYWxkLTk1MC85MCBib3JkZXIgYm9yZGVyLWVtZXJhbGQtNTAwLzUwIHRleHQtZW1lcmFsZC0zMDAgZm9udC1zZW1pYm9sZCB0ZXh0LXhzIGZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktY2VudGVyIGdhcC0yIHNoYWRvdyc7CiAgICBzdWJtaXRUZXh0LnRleHRDb250ZW50ID0gJ+KckyBJbiBMaWJyYXJ5IChBbHJlYWR5IERvd25sb2FkZWQpJzsKICAgIHN1Ym1pdEJ0bi5vbmNsaWNrID0gKCkgPT4gc2hvd1RvYXN0KCdUaXRsZSBpcyBhbHJlYWR5IGRvd25sb2FkZWQgYW5kIHJlYWR5IGluIEplbGx5ZmluLiBTa2lwcGluZyBkb3dubG9hZC4nLCAnaW5mbycpOwogIH0gZWxzZSB7CiAgICBzdWJtaXRCdG4uY2xhc3NMaXN0LnJlbW92ZSgnaGlkZGVuJyk7CiAgICBjYW5jZWxCdG4uY2xhc3NMaXN0LmFkZCgnaGlkZGVuJyk7CiAgICBzdWJtaXRCdG4uY2xhc3NOYW1lID0gJ3ctZnVsbCBweS0yLjUgcm91bmRlZC14bCBiZy1icmFuZC02MDAgaG92ZXI6YmctYnJhbmQtNTAwIHRleHQtd2hpdGUgZm9udC1zZW1pYm9sZCB0ZXh0LXhzIGZsZXggaXRlbXMtY2VudGVyIGp1c3RpZnktY2VudGVyIGdhcC0yIHNoYWRvdyB0cmFuc2l0aW9uLWFsbCBhY3RpdmU6c2NhbGUtOTUnOwogICAgc3VibWl0VGV4dC50ZXh0Q29udGVudCA9ICdSZXF1ZXN0ICcgKyAodHlwZSA9PT0gJ3R2JyA/ICdTZXJpZXMnIDogJ01vdmllJyk7CiAgICBzdWJtaXRCdG4ub25jbGljayA9ICgpID0+IGV4ZWN1dGVJbnN0YW50UmVxdWVzdCgpOwogIH0KCiAgbW9kYWwuY2xhc3NMaXN0LnJlbW92ZSgnaGlkZGVuJyk7Cn0KCmFzeW5jIGZ1bmN0aW9uIGV4ZWN1dGVJbnN0YW50UmVxdWVzdCgpIHsKICBpZiAoIWFjdGl2ZU1vZGFsSXRlbSkgcmV0dXJuOwogIGNvbnN0IGl0ZW0gPSBhY3RpdmVNb2RhbEl0ZW07CiAgY29uc3QgdGl0bGUgPSBnZXRJdGVtVGl0bGUoaXRlbSk7CiAgY29uc3QgdHlwZSA9IGdldEl0ZW1UeXBlKGl0ZW0pOwoKICAvLyBQcmV2ZW50IGR1cGxpY2F0ZSBkb3dubG9hZHMgaWYgdGl0bGUgaXMgYWxyZWFkeSBpbiBsaWJyYXJ5CiAgY29uc3QgY3VycmVudExpYlN0YXR1cyA9IGdldEl0ZW1MaWJyYXJ5U3RhdHVzKGl0ZW0pOwogIGlmIChjdXJyZW50TGliU3RhdHVzICYmIGN1cnJlbnRMaWJTdGF0dXMuaXNBdmFpbGFibGUpIHsKICAgIHNob3dUb2FzdCgnTW92aWUgaXMgYWxyZWFkeSBkb3dubG9hZGVkIGFuZCBwcmVzZW50IGluIGxpYnJhcnkhIFNraXBwaW5nIGRvd25sb2FkLicsICdpbmZvJyk7CiAgICByZXR1cm47CiAgfQogIGNvbnN0IHF1YWxpdHkgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtcXVhbGl0eScpLnZhbHVlOwogIGNvbnN0IHJvb3QgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtcm9vdCcpLnZhbHVlOwogIGNvbnN0IHNlcnZpY2UgPSAodHlwZSA9PT0gJ3R2JyA/ICdzb25hcnInIDogJ3JhZGFycicpOwoKICBjb25zdCBzdWJtaXRCdG4gPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtc3VibWl0LWJ0bicpOwogIGNvbnN0IHN1Ym1pdFRleHQgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtc3VibWl0LXRleHQnKTsKICBpZiAoc3VibWl0QnRuKSBzdWJtaXRCdG4uZGlzYWJsZWQgPSB0cnVlOwogIGlmIChzdWJtaXRUZXh0KSBzdWJtaXRUZXh0LnRleHRDb250ZW50ID0gJ1JvdXRpbmcgdG8gJyArIHNlcnZpY2UudG9VcHBlckNhc2UoKSArICcuLi4nOwoKICBjb25zdCBwYXlsb2FkID0gewogICAgdHlwZTogdHlwZSwKICAgIG1lZGlhVHlwZTogdHlwZSwKICAgIGl0ZW06IGl0ZW0sCiAgICB0aXRsZTogdGl0bGUsCiAgICBtZWRpYUlkOiBpdGVtLmlkIHx8IGl0ZW0udG1kYklkIHx8IGl0ZW0udHZkYklkLAogICAgdG1kYklkOiBpdGVtLnRtZGJJZCB8fCBpdGVtLmlkLAogICAgdHZkYklkOiBpdGVtLnR2ZGJJZCB8fCBpdGVtLmlkLAogICAgcXVhbGl0eVByb2ZpbGVJZDogcGFyc2VJbnQocXVhbGl0eSwgMTApIHx8IDQsCiAgICBwcm9maWxlSWQ6IHBhcnNlSW50KHF1YWxpdHksIDEwKSB8fCA0LAogICAgcm9vdEZvbGRlclBhdGg6IHJvb3QsCiAgICByb290Rm9sZGVyOiByb290CiAgfTsKCiAgaWYgKHR5cGUgPT09ICd0dicgJiYgbW9kYWxTZWxlY3RlZFNlYXNvbnMubGVuZ3RoID4gMCkgewogICAgcGF5bG9hZC5zZWFzb25zID0gbW9kYWxTZWxlY3RlZFNlYXNvbnM7CiAgfQoKICB0cnkgewogICAgY29uc3QgcmVzID0gYXdhaXQgYXBpUG9zdCgnL2FwaS92MS9yZXF1ZXN0JywgcGF5bG9hZCk7CiAgICBpZiAoIXJlcyB8fCByZXMuc3VjY2VzcyA9PT0gZmFsc2UpIHsKICAgICAgY29uc3QgZXJyVHh0ID0gKHJlcyAmJiByZXMubWVzc2FnZSkgPyByZXMubWVzc2FnZSA6ICdVbmtub3duIHJlamVjdGlvbiBmcm9tIHNlcnZlcic7CiAgICAgIHNob3dUb2FzdCgnUmVxdWVzdCByZWplY3RlZDogJyArIGVyclR4dCwgJ2Vycm9yJyk7CiAgICAgIGlmIChzdWJtaXRUZXh0KSBzdWJtaXRUZXh0LnRleHRDb250ZW50ID0gJ1JlcXVlc3QgRmFpbGVkJzsKICAgICAgcmV0dXJuOwogICAgfQogICAgaWYgKHJlcy5hbHJlYWR5RXhpc3RzKSB7CiAgICAgIHNob3dUb2FzdChyZXMubWVzc2FnZSB8fCAnVGl0bGUgaXMgYWxyZWFkeSBpbiBsaWJyYXJ5ISBTa2lwcGluZyBkdXBsaWNhdGUgZG93bmxvYWQuJywgJ2luZm8nKTsKICAgICAgYXdhaXQgZmV0Y2hMaWJyYXJ5KCk7CiAgICAgIGF3YWl0IGZldGNoUmVxdWVzdHNMaXN0KCk7CiAgICAgIG9wZW5JbnN0YW50TW9kYWwoaXRlbSk7CiAgICAgIHJldHVybjsKICAgIH0KICAgIGlmIChyZXMuYWxyZWFkeVBlbmRpbmcpIHsKICAgICAgc2hvd1RvYXN0KHJlcy5tZXNzYWdlIHx8ICdUaXRsZSBpcyBhbHJlYWR5IGluIFBlbmRpbmcgUmVxdWVzdHMgcXVldWUhJywgJ3dhcm5pbmcnKTsKICAgICAgYXdhaXQgZmV0Y2hSZXF1ZXN0c0xpc3QoKTsKICAgICAgb3Blbkluc3RhbnRNb2RhbChpdGVtKTsKICAgICAgcmV0dXJuOwogICAgfQogICAgaWYgKHJlcy5wZW5kaW5nKSB7CiAgICAgIHNob3dUb2FzdChyZXMubWVzc2FnZSB8fCAoc2VydmljZS50b1VwcGVyQ2FzZSgpICsgJyBvZmZsaW5lOiBTYXZlZCB0byBQZW5kaW5nIFJlcXVlc3RzICh3aWxsIGF1dG8tZGlzcGF0Y2ggb24gc3RhcnQpJyksICd3YXJuaW5nJyk7CiAgICAgIGlmIChzdWJtaXRUZXh0KSBzdWJtaXRUZXh0LnRleHRDb250ZW50ID0gJ1NhdmVkIHRvIFBlbmRpbmchJzsKICAgICAgYXdhaXQgZmV0Y2hSZXF1ZXN0c0xpc3QoKTsKICAgICAgc2V0VGltZW91dCgoKSA9PiB7CiAgICAgICAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2RldGFpbHMtbW9kYWwnKS5jbGFzc0xpc3QuYWRkKCdoaWRkZW4nKTsKICAgICAgICBpZiAoaGVyb0l0ZW0gJiYgZ2V0SXRlbVRpdGxlKGhlcm9JdGVtKSA9PT0gdGl0bGUpIHJlbmRlckhlcm8oaGVyb0l0ZW0pOwogICAgICAgIGlmIChjdXJyZW50VHJlbmRpbmcubGVuZ3RoID4gMCkgcmVuZGVyR3JpZCgndHJlbmRpbmctZ3JpZCcsIGN1cnJlbnRUcmVuZGluZy5zbGljZSgwLCAxNikpOwogICAgICB9LCAxMDAwKTsKICAgICAgcmV0dXJuOwogICAgfQogICAgc2hvd1RvYXN0KCdSb3V0ZWQgdG8gJyArIHNlcnZpY2UudG9VcHBlckNhc2UoKSArICc6ICInICsgdGl0bGUgKyAnIicsICdzdWNjZXNzJyk7CiAgICBpZiAoc3VibWl0VGV4dCkgc3VibWl0VGV4dC50ZXh0Q29udGVudCA9ICdSZXF1ZXN0ZWQgU3VjY2Vzc2Z1bGx5ISc7CiAgICBhd2FpdCBmZXRjaFJlcXVlc3RzTGlzdCgpOwogICAgYXdhaXQgZmV0Y2hMaWJyYXJ5KCk7CiAgICBzZXRUaW1lb3V0KCgpID0+IHsKICAgICAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2RldGFpbHMtbW9kYWwnKS5jbGFzc0xpc3QuYWRkKCdoaWRkZW4nKTsKICAgICAgaWYgKGhlcm9JdGVtICYmIGdldEl0ZW1UaXRsZShoZXJvSXRlbSkgPT09IHRpdGxlKSByZW5kZXJIZXJvKGhlcm9JdGVtKTsKICAgICAgaWYgKGN1cnJlbnRUcmVuZGluZy5sZW5ndGggPiAwKSByZW5kZXJHcmlkKCd0cmVuZGluZy1ncmlkJywgY3VycmVudFRyZW5kaW5nLnNsaWNlKDAsIDE2KSk7CiAgICB9LCA4MDApOwogIH0gY2F0Y2ggKGVycikgewogICAgc2hvd1RvYXN0KCdSZXF1ZXN0IGZhaWxlZDogJyArIChlcnIubWVzc2FnZSB8fCAnTmV0d29yayBlcnJvcicpLCAnZXJyb3InKTsKICAgIGlmIChzdWJtaXRUZXh0KSBzdWJtaXRUZXh0LnRleHRDb250ZW50ID0gJ1JlcXVlc3QgRmFpbGVkJzsKICB9IGZpbmFsbHkgewogICAgaWYgKHN1Ym1pdEJ0bikgc3VibWl0QnRuLmRpc2FibGVkID0gZmFsc2U7CiAgfQp9Cgpkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnbW9kYWwtY2xvc2UtYnRuJykuYWRkRXZlbnRMaXN0ZW5lcignY2xpY2snLCAoKSA9PiB7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2RldGFpbHMtbW9kYWwnKS5jbGFzc0xpc3QuYWRkKCdoaWRkZW4nKTsKfSk7CgovLyBTZWFyY2gKbGV0IHNlYXJjaERlYm91bmNlVGltZXIgPSBudWxsOwpjb25zdCBzZWFyY2hJbnB1dCA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdzZWFyY2gtaW5wdXQnKTsKY29uc3Qgc2VhcmNoQ2xlYXIgPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnc2VhcmNoLWNsZWFyJyk7CmNvbnN0IHNlYXJjaFNlY3Rpb24gPSBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnc2VhcmNoLXNlY3Rpb24nKTsKCnNlYXJjaElucHV0LmFkZEV2ZW50TGlzdGVuZXIoJ2lucHV0JywgKCkgPT4gewogIGNvbnN0IHF1ZXJ5ID0gc2VhcmNoSW5wdXQudmFsdWUudHJpbSgpOwogIGlmIChxdWVyeS5sZW5ndGggPiAwKSB7CiAgICBzZWFyY2hDbGVhci5jbGFzc0xpc3QucmVtb3ZlKCdoaWRkZW4nKTsKICB9IGVsc2UgewogICAgc2VhcmNoQ2xlYXIuY2xhc3NMaXN0LmFkZCgnaGlkZGVuJyk7CiAgICBzZWFyY2hTZWN0aW9uLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwogICAgcmV0dXJuOwogIH0KCiAgY2xlYXJUaW1lb3V0KHNlYXJjaERlYm91bmNlVGltZXIpOwogIHNlYXJjaERlYm91bmNlVGltZXIgPSBzZXRUaW1lb3V0KGFzeW5jICgpID0+IHsKICAgIGlmIChxdWVyeS5sZW5ndGggPCAyKSByZXR1cm47CiAgICB0cnkgewogICAgICBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnc2VhcmNoLXF1ZXJ5LWJhZGdlJykudGV4dENvbnRlbnQgPSAnIicgKyBxdWVyeSArICciJzsKICAgICAgc2VhcmNoU2VjdGlvbi5jbGFzc0xpc3QucmVtb3ZlKCdoaWRkZW4nKTsKICAgICAgY29uc3QgZGF0YSA9IGF3YWl0IGFwaUdldCgnL2FwaS92MS9zZWFyY2g/cT0nICsgZW5jb2RlVVJJQ29tcG9uZW50KHF1ZXJ5KSk7CiAgICAgIGNvbnN0IHJlc3VsdHMgPSAoZGF0YSAmJiBkYXRhLnJlc3VsdHMpID8gZGF0YS5yZXN1bHRzIDogKEFycmF5LmlzQXJyYXkoZGF0YSkgPyBkYXRhIDogW10pOwogICAgICByZW5kZXJHcmlkKCdzZWFyY2gtZ3JpZCcsIHJlc3VsdHMpOwogICAgICBzZWFyY2hTZWN0aW9uLnNjcm9sbEludG9WaWV3KHsgYmVoYXZpb3I6ICdzbW9vdGgnLCBibG9jazogJ3N0YXJ0JyB9KTsKICAgIH0gY2F0Y2ggKGUpIHsKICAgICAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ3NlYXJjaC1ncmlkJykuaW5uZXJIVE1MID0gJzxkaXYgY2xhc3M9ImNvbC1zcGFuLWZ1bGwgcHktNCB0ZXh0LWNlbnRlciB0ZXh0LWdyYXktNTAwIHRleHQteHMiPlNlYXJjaCB1bmF2YWlsYWJsZTwvZGl2Pic7CiAgICB9CiAgfSwgMzAwKTsKfSk7CgpzZWFyY2hDbGVhci5hZGRFdmVudExpc3RlbmVyKCdjbGljaycsICgpID0+IHsKICBzZWFyY2hJbnB1dC52YWx1ZSA9ICcnOwogIHNlYXJjaENsZWFyLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwogIHNlYXJjaFNlY3Rpb24uY2xhc3NMaXN0LmFkZCgnaGlkZGVuJyk7Cn0pOwoKZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ3NlYXJjaC1jbG9zZS1idG4nKS5hZGRFdmVudExpc3RlbmVyKCdjbGljaycsICgpID0+IHsKICBzZWFyY2hTZWN0aW9uLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwp9KTsKCi8vIERvd25sb2Fkcwphc3luYyBmdW5jdGlvbiBmZXRjaERvd25sb2FkcygpIHsKICB0cnkgewogICAgY29uc3QgdG9ycmVudHMgPSBhd2FpdCBhcGlHZXQoJy9hcGkvdjEvZG93bmxvYWRzJyk7CiAgICByZW5kZXJEb3dubG9hZHModG9ycmVudHMgfHwgW10pOwogIH0gY2F0Y2ggKGUpIHt9Cn0KCmZ1bmN0aW9uIHJlbmRlckRvd25sb2Fkcyh0b3JyZW50cykgewogIGNvbnN0IGNvbnRhaW5lciA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdkb3dubG9hZHMtbGVkZ2VyLWxpc3QnKTsKICBjb25zdCBjb3VudEJhZGdlID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2RsLWNvdW50Jyk7CiAgY29uc3Qgc3BlZWRCYWRnZSA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCd0YWItZGwtc3BlZWQnKTsKCiAgaWYgKGNvdW50QmFkZ2UpIGNvdW50QmFkZ2UudGV4dENvbnRlbnQgPSB0b3JyZW50cy5sZW5ndGg7CiAgbGV0IHRvdGFsU3BlZWQgPSAwOwogIHRvcnJlbnRzLmZvckVhY2godCA9PiB0b3RhbFNwZWVkICs9ICh0LnJhdGVEb3dubG9hZCB8fCAwKSk7CiAgaWYgKHNwZWVkQmFkZ2UpIHNwZWVkQmFkZ2UudGV4dENvbnRlbnQgPSAodG90YWxTcGVlZCAvIDEwNDg1NzYpLnRvRml4ZWQoMik7CgogIGlmICghY29udGFpbmVyKSByZXR1cm47CiAgaWYgKHRvcnJlbnRzLmxlbmd0aCA9PT0gMCkgewogICAgY29udGFpbmVyLmlubmVySFRNTCA9ICc8ZGl2IGNsYXNzPSJ0ZXh0LWNlbnRlciBweS0xMiB0ZXh0LWdyYXktNTAwIHRleHQteHMiPlRyYW5zbWlzc2lvbiBpZGxlIOKAoiBaZXJvIGFjdGl2ZSBkb3dubG9hZHM8L2Rpdj4nOwogICAgcmV0dXJuOwogIH0KCiAgbGV0IGh0bWwgPSAnJzsKICB0b3JyZW50cy5mb3JFYWNoKHQgPT4gewogICAgY29uc3QgcGN0ID0gKCh0LnBlcmNlbnREb25lIHx8IDApICogMTAwKS50b0ZpeGVkKDEpOwogICAgY29uc3Qgc3BlZWQgPSAoKHQucmF0ZURvd25sb2FkIHx8IDApIC8gMTA0ODU3NikudG9GaXhlZCgyKSArICcgTUIvcyc7CiAgICBjb25zdCBpc1BhdXNlZCA9IHQuc3RhdHVzID09PSAwOwoKICAgIGh0bWwgKz0gCiAgICAgICc8ZGl2IGNsYXNzPSJnbGFzcy1jYXJkIHJvdW5kZWQteGwgcC0zIHNwYWNlLXktMS41IGJvcmRlciBib3JkZXItZ3JheS04MDAiPicgKwogICAgICAgICc8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4gdGV4dC14cyI+JyArCiAgICAgICAgICAnPGRpdiBjbGFzcz0iZm9udC1tZWRpdW0gdGV4dC1ncmF5LTIwMCB0cnVuY2F0ZSIgdGl0bGU9IicgKyB0Lm5hbWUgKyAnIj4nICsgdC5uYW1lICsgJzwvZGl2PicgKwogICAgICAgICAgJzxkaXYgY2xhc3M9ImZvbnQtbW9ubyB0ZXh0LWdyYXktNDAwIGZvbnQtYm9sZCI+JyArIHBjdCArICclPC9kaXY+JyArCiAgICAgICAgJzwvZGl2PicgKwogICAgICAgICc8ZGl2IGNsYXNzPSJkbC10cmFjayI+JyArCiAgICAgICAgICAnPGRpdiBjbGFzcz0iZGwtZmlsbCAnICsgKGlzUGF1c2VkID8gJ3BhdXNlZCcgOiAnJykgKyAnIiBzdHlsZT0id2lkdGg6ICcgKyBwY3QgKyAnJSI+PC9kaXY+JyArCiAgICAgICAgJzwvZGl2PicgKwogICAgICAgICc8ZGl2IGNsYXNzPSJmbGV4IGl0ZW1zLWNlbnRlciBqdXN0aWZ5LWJldHdlZW4gdGV4dC1bMTBweF0gdGV4dC1ncmF5LTQwMCI+JyArCiAgICAgICAgICAnPHNwYW4+JyArIHNwZWVkICsgJyDigKIgJyArIChpc1BhdXNlZCA/ICdQQVVTRUQnIDogJ0RPV05MT0FESU5HJykgKyAnPC9zcGFuPicgKwogICAgICAgICAgJzxkaXYgY2xhc3M9ImZsZXggaXRlbXMtY2VudGVyIGdhcC0yIj4nICsKICAgICAgICAgICAgJzxidXR0b24gY2xhc3M9InB4LTIgcHktMC41IHJvdW5kZWQgYm9yZGVyIGJvcmRlci1ncmF5LTgwMCB0ZXh0LWdyYXktMjAwIHRleHQtWzEwcHhdIiBvbmNsaWNrPSJ0b2dnbGVUb3JyZW50UGF1c2UoJyArIHQuaWQgKyAnLCAnICsgaXNQYXVzZWQgKyAnKSI+JyArIChpc1BhdXNlZCA/ICdSZXN1bWUnIDogJ1BhdXNlJykgKyAnPC9idXR0b24+JyArCiAgICAgICAgICAgICc8YnV0dG9uIGNsYXNzPSJweC0yIHB5LTAuNSByb3VuZGVkIGJnLXJvc2UtOTUwLzYwIGJvcmRlciBib3JkZXItcm9zZS01MDAvMzAgdGV4dC1yb3NlLTMwMCB0ZXh0LVsxMHB4XSIgb25jbGljaz0iZGVsZXRlVG9ycmVudEl0ZW0oJyArIHQuaWQgKyAnKSI+RGVsZXRlPC9idXR0b24+JyArCiAgICAgICAgICAnPC9kaXY+JyArCiAgICAgICAgJzwvZGl2PicgKwogICAgICAnPC9kaXY+JzsKICB9KTsKICBjb250YWluZXIuaW5uZXJIVE1MID0gaHRtbDsKfQoKYXN5bmMgZnVuY3Rpb24gdG9nZ2xlVG9ycmVudFBhdXNlKGlkLCBpc1BhdXNlZCkgewogIGNvbnN0IGVuZHBvaW50ID0gaXNQYXVzZWQgPyAnL2FwaS92MS9kb3dubG9hZHMvcmVzdW1lJyA6ICcvYXBpL3YxL2Rvd25sb2Fkcy9wYXVzZSc7CiAgYXdhaXQgYXBpUG9zdChlbmRwb2ludCwgeyBpZDogaWQgfSk7CiAgZmV0Y2hEb3dubG9hZHMoKTsKfQoKYXN5bmMgZnVuY3Rpb24gZGVsZXRlVG9ycmVudEl0ZW0oaWQpIHsKICBpZiAoIWNvbmZpcm0oJ1JlbW92ZSBkb3dubG9hZCBhbmQgZGVsZXRlIGZpbGVzIGZyb20gc3RvcmFnZT8nKSkgcmV0dXJuOwogIGF3YWl0IGFwaVBvc3QoJy9hcGkvdjEvZG93bmxvYWRzL2RlbGV0ZScsIHsgaWQ6IGlkLCBkZWxldGVEYXRhOiB0cnVlIH0pOwogIHNob3dUb2FzdCgnRG93bmxvYWQgcmVtb3ZlZCcsICdpbmZvJyk7CiAgZmV0Y2hEb3dubG9hZHMoKTsKfQoKLy8gU2V0dGluZ3MKY29uc3Qgc2V0dGluZ3NNb2RhbCA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdzZXR0aW5ncy1tb2RhbCcpOwpkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnYnRuLW9wZW4tc2V0dGluZ3MnKS5hZGRFdmVudExpc3RlbmVyKCdjbGljaycsICgpID0+IHsKICBkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnY2ZnLWhvc3QnKS52YWx1ZSA9IEJBU0VfVVJMOwogIGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdjZmctYXBpLWtleScpLnZhbHVlID0gbG9jYWxTdG9yYWdlLmdldEl0ZW0oJ2plbGx5c2VlcnJfYXBpX2tleScpIHx8ICcnOwogIHNldHRpbmdzTW9kYWwuY2xhc3NMaXN0LnJlbW92ZSgnaGlkZGVuJyk7Cn0pOwpkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnc2V0dGluZ3MtY2xvc2UtYnRuJykuYWRkRXZlbnRMaXN0ZW5lcignY2xpY2snLCAoKSA9PiB7CiAgc2V0dGluZ3NNb2RhbC5jbGFzc0xpc3QuYWRkKCdoaWRkZW4nKTsKfSk7CmRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdjZmctc2F2ZS1idG4nKS5hZGRFdmVudExpc3RlbmVyKCdjbGljaycsICgpID0+IHsKICBjb25zdCBuZXdIb3N0ID0gZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2NmZy1ob3N0JykudmFsdWUudHJpbSgpOwogIGNvbnN0IG5ld0tleSA9IGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdjZmctYXBpLWtleScpLnZhbHVlLnRyaW0oKTsKICBpZiAobmV3SG9zdCkgewogICAgbG9jYWxTdG9yYWdlLnNldEl0ZW0oJ2plbGx5c2VlcnJfaG9zdCcsIG5ld0hvc3QpOwogICAgQkFTRV9VUkwgPSBuZXdIb3N0OwogIH0KICBpZiAobmV3S2V5KSBsb2NhbFN0b3JhZ2Uuc2V0SXRlbSgnamVsbHlzZWVycl9hcGlfa2V5JywgbmV3S2V5KTsKICBlbHNlIGxvY2FsU3RvcmFnZS5yZW1vdmVJdGVtKCdqZWxseXNlZXJyX2FwaV9rZXknKTsKICBzZXR0aW5nc01vZGFsLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwogIHNob3dUb2FzdCgnU2F2ZWQgZW5kcG9pbnQ6ICcgKyBCQVNFX1VSTCwgJ3N1Y2Nlc3MnKTsKICBsb2FkKCk7Cn0pOwpkb2N1bWVudC5nZXRFbGVtZW50QnlJZCgnY2ZnLXJlc2V0LWJ0bicpLmFkZEV2ZW50TGlzdGVuZXIoJ2NsaWNrJywgKCkgPT4gewogIGxvY2FsU3RvcmFnZS5yZW1vdmVJdGVtKCdqZWxseXNlZXJyX2hvc3QnKTsKICBsb2NhbFN0b3JhZ2UucmVtb3ZlSXRlbSgnamVsbHlzZWVycl9hcGlfa2V5Jyk7CiAgQkFTRV9VUkwgPSByZXNvbHZlQmFzZVVybCgpOwogIGRvY3VtZW50LmdldEVsZW1lbnRCeUlkKCdjZmctaG9zdCcpLnZhbHVlID0gQkFTRV9VUkw7CiAgZG9jdW1lbnQuZ2V0RWxlbWVudEJ5SWQoJ2NmZy1hcGkta2V5JykudmFsdWUgPSAnJzsKICBzZXR0aW5nc01vZGFsLmNsYXNzTGlzdC5hZGQoJ2hpZGRlbicpOwogIHNob3dUb2FzdCgnUmVzZXQgdG8gZGVmYXVsdCBlbmRwb2ludCcsICdpbmZvJyk7CiAgbG9hZCgpOwp9KTsKCi8vIEJvb3QKbG9hZCgpOwpzZXRJbnRlcnZhbCgoKSA9PiB7CiAgZmV0Y2hTZXJ2aWNlc0xpc3QoKTsKICBmZXRjaERvd25sb2FkcygpOwp9LCA4MDAwKTsKc2V0SW50ZXJ2YWwoKCkgPT4gewogIGZldGNoUmVxdWVzdHNMaXN0KCk7CiAgZmV0Y2hMaWJyYXJ5KCk7Cn0sIDI1MDAwKTsKPC9zY3JpcHQ+CjwvYm9keT4KPC9odG1sPg==";
const HTML_APP = Buffer.from(EMBEDDED_HTML_B64, 'base64').toString('utf8');

function getAppHtml() {
    const localHtml = path.join(__dirname, 'index.html');
    try {
        if (fs.existsSync(localHtml)) {
            return fs.readFileSync(localHtml, 'utf8');
        }
    } catch (e) {}
    return HTML_APP;
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPREHENSIVE REST API & ROUTING ENGINE
// ─────────────────────────────────────────────────────────────────────────────

const REQUESTS_FILE = path.join(CONFIG_DIR, 'requests.json');
let requestHistory = [];
try {
    if (fs.existsSync(REQUESTS_FILE)) {
        requestHistory = JSON.parse(fs.readFileSync(REQUESTS_FILE, 'utf8'));
    }
} catch (e) {}

function isMovieInDiskLibrary(title, year) {
    if (!title) return false;
    const pathsToCheck = [
        '/storage/emulated/0/FishbowlMedia/Movies',
        '/sdcard/FishbowlMedia/Movies'
    ];
    const normTitle = title.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
    if (normTitle.length < 3) return false;
    const yearStr = year ? String(year).trim() : '';

    for (const p of pathsToCheck) {
        try {
            if (fs.existsSync(p)) {
                const files = fs.readdirSync(p);
                for (const f of files) {
                    const cleanF = f.toLowerCase().replace(/[^a-z0-9]/g, '');
                    if (cleanF === normTitle) return true;
                    if (yearStr && cleanF === (normTitle + yearStr)) return true;
                    if (cleanF.startsWith(normTitle) && yearStr && cleanF.includes(yearStr)) return true;
                }
            }
        } catch (e) {}
    }
    return false;
}

function isTvInDiskLibrary(title, year) {
    if (!title) return false;
    const pathsToCheck = [
        '/storage/emulated/0/FishbowlMedia/TV',
        '/sdcard/FishbowlMedia/TV'
    ];
    const normTitle = title.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
    if (normTitle.length < 3) return false;
    const yearStr = year ? String(year).trim() : '';

    for (const p of pathsToCheck) {
        try {
            if (fs.existsSync(p)) {
                const files = fs.readdirSync(p);
                for (const f of files) {
                    const cleanF = f.toLowerCase().replace(/[^a-z0-9]/g, '');
                    if (cleanF === normTitle) return true;
                    if (yearStr && cleanF === (normTitle + yearStr)) return true;
                    if (cleanF.startsWith(normTitle) && yearStr && cleanF.includes(yearStr)) return true;
                }
            }
        } catch (e) {}
    }
    return false;
}

function recordRequest(item, type, profileId, status = 'approved', extra = {}) {
    try {
        const titleStr = item.title || item.name || 'Untitled';
        const norm = titleStr.trim().toLowerCase();
        const tmdb = item.tmdbId ? parseInt(item.tmdbId, 10) : 0;
        const tvdb = item.tvdbId ? parseInt(item.tvdbId, 10) : 0;

        // Deduplicate prior requests
        requestHistory = requestHistory.filter(r => {
            if (tmdb && r.tmdbId === tmdb) return false;
            if (tvdb && r.tvdbId === tvdb) return false;
            if (norm && (r.title || '').trim().toLowerCase() === norm) return false;
            return true;
        });

        const entry = {
            id: extra.id || Date.now(),
            title: titleStr,
            type: type,
            year: item.year || '',
            tmdbId: tmdb || undefined,
            tvdbId: tvdb || undefined,
            remotePoster: item.remotePoster || item.poster || '',
            backdrop: item.backdrop || '',
            requestedAt: extra.requestedAt || new Date().toISOString(),
            status: status, // 'pending' | 'approved' | 'in_library'
            qualityProfileId: profileId,
            rootFolderPath: extra.rootFolderPath || undefined,
            titleSlug: extra.titleSlug || undefined,
            seasons: extra.seasons || undefined,
            lastError: extra.lastError || undefined
        };

        requestHistory.unshift(entry);
        if (requestHistory.length > 200) requestHistory = requestHistory.slice(0, 200);
        fs.writeFileSync(REQUESTS_FILE, JSON.stringify(requestHistory, null, 2), 'utf8');
        return entry;
    } catch (e) {
        return null;
    }
}

let isDispatchingPending = false;
async function dispatchPendingRequests() {
    if (isDispatchingPending) return { dispatched: 0, pending: 0 };
    isDispatchingPending = true;

    try {
        const pendingItems = requestHistory.filter(r => r.status === 'pending');
        if (pendingItems.length === 0) return { dispatched: 0, pending: 0 };

        const keys = getStackKeys();
        let dispatchedCount = 0;

        // Check Radarr pending movie requests
        const pendingMovies = pendingItems.filter(r => r.type === 'movie');
        if (pendingMovies.length > 0 && keys.radarr) {
            const radarrOnline = await probePort(7878, '/api/v3/system/status');
            if (radarrOnline) {
                const existingMovies = await getRadarrExistingMovies(keys.radarr);
                const radarrProfiles = await getRadarrQualityProfiles(keys.radarr);
                const rootFolders = await getRadarrRootFolders(keys.radarr);
                const defProfile = radarrProfiles.find(p => (p.name || '').toLowerCase().includes('1080p')) || radarrProfiles[0] || { id: 4 };
                const defRoot = rootFolders[0] || '/storage/emulated/0/FishbowlMedia/Movies';

                for (const item of pendingMovies) {
                    try {
                        const tmdbIdNum = parseInt(item.tmdbId, 10);
                        const normTitle = (item.title || '').trim().toLowerCase();

                        // 1. Check if already exists in Radarr
                        const existingMatch = existingMovies.find(m => 
                            (tmdbIdNum && m.tmdbId === tmdbIdNum) ||
                            (m.title && m.title.trim().toLowerCase() === normTitle)
                        );
                        if (existingMatch) {
                            item.status = existingMatch.hasFile ? 'in_library' : 'approved';
                            item.dispatchedAt = new Date().toISOString();
                            dispatchedCount++;
                            continue;
                        }

                        // 2. Check disk library
                        if (isMovieInDiskLibrary(item.title, item.year)) {
                            item.status = 'in_library';
                            item.dispatchedAt = new Date().toISOString();
                            dispatchedCount++;
                            continue;
                        }

                        // 3. Resolve quality profile
                        let selectedProfileId = item.qualityProfileId;
                        if (!radarrProfiles.some(p => p.id === selectedProfileId)) {
                            selectedProfileId = defProfile.id;
                        }

                        const cleanSlug = normTitle.replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
                        const moviePayload = {
                            title: item.title,
                            tmdbId: tmdbIdNum,
                            year: parseInt(item.year, 10) || undefined,
                            qualityProfileId: selectedProfileId,
                            rootFolderPath: item.rootFolderPath || defRoot,
                            monitored: true,
                            titleSlug: item.titleSlug || (cleanSlug + '-' + tmdbIdNum),
                            addOptions: { searchForMovie: true }
                        };

                        const imgs = [];
                        if (item.remotePoster) imgs.push({ coverType: 'poster', url: item.remotePoster, remoteUrl: item.remotePoster });
                        if (item.backdrop) imgs.push({ coverType: 'fanart', url: item.backdrop, remoteUrl: item.backdrop });
                        if (imgs.length > 0) moviePayload.images = imgs;

                        const radarrRes = await fetchJson(`http://127.0.0.1:7878/api/v3/movie?apiKey=${keys.radarr}`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Api-Key': keys.radarr
                            },
                            body: moviePayload
                        });

                        item.status = 'approved';
                        item.dispatchedAt = new Date().toISOString();
                        item.radarrId = radarrRes.id;
                        delete item.lastError;
                        dispatchedCount++;
                        console.log(`[AutoDispatch] Successfully auto-routed pending movie "${item.title}" to Radarr (ID: ${radarrRes.id})`);
                    } catch (err) {
                        const msg = (err.message || '').toLowerCase();
                        if (msg.includes('already exists') || msg.includes('movieexistsvalidator')) {
                            item.status = 'approved';
                            item.dispatchedAt = new Date().toISOString();
                            dispatchedCount++;
                        } else {
                            item.lastError = err.message;
                            console.error(`[AutoDispatch] Error dispatching "${item.title}" to Radarr:`, err.message);
                        }
                    }
                }
            }
        }

        // Check Sonarr pending TV requests
        const pendingTv = pendingItems.filter(r => r.type === 'tv');
        if (pendingTv.length > 0 && keys.sonarr) {
            const sonarrOnline = await probePort(8989, '/api/v3/system/status');
            if (sonarrOnline) {
                const existingSeries = await getSonarrExistingSeries(keys.sonarr);
                const sonarrProfiles = await getSonarrQualityProfiles(keys.sonarr);
                const rootFolders = await getSonarrRootFolders(keys.sonarr);
                const defProfile = sonarrProfiles.find(p => (p.name || '').toLowerCase().includes('1080p')) || sonarrProfiles[0] || { id: 4 };
                const defRoot = rootFolders[0] || '/storage/emulated/0/FishbowlMedia/TV';

                for (const item of pendingTv) {
                    try {
                        const tvdbIdNum = parseInt(item.tvdbId, 10);
                        const normTitle = (item.title || '').trim().toLowerCase();

                        const existingMatch = existingSeries.find(s => 
                            (tvdbIdNum && s.tvdbId === tvdbIdNum) ||
                            (s.title && s.title.trim().toLowerCase() === normTitle)
                        );
                        if (existingMatch) {
                            item.status = 'approved';
                            item.dispatchedAt = new Date().toISOString();
                            dispatchedCount++;
                            continue;
                        }

                        if (isTvInDiskLibrary(item.title, item.year)) {
                            item.status = 'in_library';
                            item.dispatchedAt = new Date().toISOString();
                            dispatchedCount++;
                            continue;
                        }

                        let selectedProfileId = item.qualityProfileId;
                        if (!sonarrProfiles.some(p => p.id === selectedProfileId)) {
                            selectedProfileId = defProfile.id;
                        }

                        const cleanSlug = normTitle.replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
                        const tvPayload = {
                            title: item.title,
                            tvdbId: tvdbIdNum,
                            year: parseInt(item.year, 10) || undefined,
                            qualityProfileId: selectedProfileId,
                            languageProfileId: 1,
                            rootFolderPath: item.rootFolderPath || defRoot,
                            monitored: true,
                            titleSlug: item.titleSlug || (cleanSlug + '-' + tvdbIdNum),
                            seasons: item.seasons || [],
                            addOptions: { searchForMissingEpisodes: true }
                        };

                        const imgs = [];
                        if (item.remotePoster) imgs.push({ coverType: 'poster', url: item.remotePoster, remoteUrl: item.remotePoster });
                        if (item.backdrop) imgs.push({ coverType: 'fanart', url: item.backdrop, remoteUrl: item.backdrop });
                        if (imgs.length > 0) tvPayload.images = imgs;

                        const sonarrRes = await fetchJson(`http://127.0.0.1:8989/api/v3/series?apiKey=${keys.sonarr}`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Api-Key': keys.sonarr
                            },
                            body: tvPayload
                        });

                        item.status = 'approved';
                        item.dispatchedAt = new Date().toISOString();
                        item.sonarrId = sonarrRes.id;
                        delete item.lastError;
                        dispatchedCount++;
                        console.log(`[AutoDispatch] Successfully auto-routed pending TV series "${item.title}" to Sonarr (ID: ${sonarrRes.id})`);
                    } catch (err) {
                        const msg = (err.message || '').toLowerCase();
                        if (msg.includes('already exists') || msg.includes('seriesexistsvalidator')) {
                            item.status = 'approved';
                            item.dispatchedAt = new Date().toISOString();
                            dispatchedCount++;
                        } else {
                            item.lastError = err.message;
                            console.error(`[AutoDispatch] Error dispatching "${item.title}" to Sonarr:`, err.message);
                        }
                    }
                }
            }
        }

        if (dispatchedCount > 0) {
            fs.writeFileSync(REQUESTS_FILE, JSON.stringify(requestHistory, null, 2), 'utf8');
        }

        const remainingPending = requestHistory.filter(r => r.status === 'pending').length;
        return { dispatched: dispatchedCount, pending: remainingPending };
    } finally {
        isDispatchingPending = false;
    }
}

// Background auto-dispatch loop: check every 12 seconds
setInterval(() => {
    dispatchPendingRequests().catch(() => {});
}, 12000);

const server = http.createServer(async (req, res) => {
    const parsed = url.parse(req.url, true);
    const pathname = parsed.pathname;

    // Standard CORS Headers
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    // 1. Health Status
    if (pathname === '/api/v1/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            version: '2.3.0',
            commitTag: 'v2.3.0',
            status: 'ok',
            requestsCount: requestHistory.length,
            app: 'Fishbowl Seerr (Jellyseerr/Overseerr Discovery Engine)',
            uptime: process.uptime(),
            nodeVersion: process.version
        }));
        return;
    }

    // 2. Stack Health Status
    if (pathname === '/api/v1/stack') {
        const [jf, radarr, sonarr, prowlarr, transmission] = await Promise.all([
            probePort(8096, '/System/Info/Public'),
            probePort(7878, '/'),
            probePort(8989, '/'),
            probePort(9696, '/'),
            probePort(9091, '/transmission/web/')
        ]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            jellyfin: jf,
            radarr: radarr,
            sonarr: sonarr,
            prowlarr: prowlarr,
            transmission: transmission
        }));
        return;
    }

    // 2b. Full Services Roster
    if (pathname === '/api/v1/services') {
        const [jf, radarr, sonarr, prowlarr, transmission, bazarr, lidarr] = await Promise.all([
            probePort(8096, '/System/Info/Public'),
            probePort(7878, '/'),
            probePort(8989, '/'),
            probePort(9696, '/'),
            probePort(9091, '/transmission/web/'),
            probePort(6767, '/'),
            probePort(8686, '/')
        ]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            services: [
                { id: 'jellyfin', name: 'Jellyfin Media Server', port: 8096, url: 'http://127.0.0.1:8096', online: jf, category: 'Core Streaming Engine' },
                { id: 'radarr', name: 'Radarr Movies', port: 7878, url: 'http://127.0.0.1:7878', online: radarr, category: 'Movie Automation' },
                { id: 'sonarr', name: 'Sonarr TV Series', port: 8989, url: 'http://127.0.0.1:8989', online: sonarr, category: 'TV Series Automation' },
                { id: 'transmission', name: 'Transmission BitTorrent', port: 9091, url: 'http://127.0.0.1:9091/transmission/web/', online: transmission, category: 'Download Client' },
                { id: 'prowlarr', name: 'Prowlarr Indexer Manager', port: 9696, url: 'http://127.0.0.1:9696', online: prowlarr, category: 'Torznab & Usenet Indexers' },
                { id: 'bazarr', name: 'Bazarr Subtitles', port: 6767, url: 'http://127.0.0.1:6767', online: bazarr, category: 'Subtitle Automation' },
                { id: 'lidarr', name: 'Lidarr Music', port: 8686, url: 'http://127.0.0.1:8686', online: lidarr, category: 'Music Automation' }
            ]
        }));
        return;
    }

    // 3. Quality Profiles
    if (pathname === '/api/v1/quality-profiles') {
        const keys = getStackKeys();
        const [radarrProfiles, sonarrProfiles] = await Promise.all([
            keys.radarr ? getRadarrQualityProfiles(keys.radarr) : [],
            keys.sonarr ? getSonarrQualityProfiles(keys.sonarr) : []
        ]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            radarr: radarrProfiles,
            sonarr: sonarrProfiles
        }));
        return;
    }

    // 4. Root Storage Folders
    if (pathname === '/api/v1/root-folders') {
        const keys = getStackKeys();
        const [radarrFolders, sonarrFolders] = await Promise.all([
            keys.radarr ? getRadarrRootFolders(keys.radarr) : [],
            keys.sonarr ? getSonarrRootFolders(keys.sonarr) : []
        ]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            radarr: radarrFolders,
            sonarr: sonarrFolders
        }));
        return;
    }

    // 5. Existing Media Library State (Radarr & Sonarr)
    if (pathname === '/api/v1/library') {
        const keys = getStackKeys();
        const [movies, series] = await Promise.all([
            keys.radarr ? getRadarrExistingMovies(keys.radarr) : [],
            keys.sonarr ? getSonarrExistingSeries(keys.sonarr) : []
        ]);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            movies: movies,
            series: series
        }));
        return;
    }

    // 6. Discover: Trending
    if (pathname === '/api/v1/discover/trending') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ results: CURATED_MEDIA }));
        return;
    }

    // 7. Discover: Movies
    if (pathname === '/api/v1/discover/movies') {
        const movies = CURATED_MEDIA.filter(m => m.type === 'movie');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ results: movies }));
        return;
    }

    // 8. Discover: TV Shows
    if (pathname === '/api/v1/discover/tv') {
        const tv = CURATED_MEDIA.filter(m => m.type === 'tv');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ results: tv }));
        return;
    }

    // 9. Discover: Upcoming
    if (pathname === '/api/v1/discover/upcoming') {
        const upcoming = CURATED_MEDIA.filter(m => (m.year || 0) >= 2025);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ results: upcoming }));
        return;
    }

    // 10. Live Search API (Decoupled & Aggregated)
    if (pathname === '/api/v1/search') {
        const q = (parsed.query.q || parsed.query.query || '').trim();
        const keys = getStackKeys();
        let results = [];

        if (q) {
            const queryLow = q.toLowerCase();

            // 1. Live TMDB Search (Global catalog lookup - zero API keys required)
            try {
                const tmdbUrl = `https://www.themoviedb.org/search/multi?query=${encodeURIComponent(q)}`;
                const tmdbData = await fetchJson(tmdbUrl, {
                    headers: {
                        'Accept': 'application/json',
                        'Accept-Language': 'en-US,en;q=0.9',
                        'User-Agent': 'curl/8.12.0-DEV'
                    },
                    timeout: 4500
                });

                if (tmdbData && Array.isArray(tmdbData.results)) {
                    tmdbData.results.forEach(item => {
                        if (item.media_type === 'person') return;
                        if (!item.title && !item.name) return;

                        const mType = item.media_type === 'tv' ? 'tv' : 'movie';
                        const title = item.title || item.name;
                        const dateStr = item.release_date || item.first_air_date || '';
                        let year = 0;
                        if (dateStr && dateStr.length >= 4) {
                            try { year = parseInt(dateStr.substring(0, 4), 10); } catch(e) {}
                        }

                        const posterUrl = item.poster_path 
                            ? `https://image.tmdb.org/t/p/w500${item.poster_path}`
                            : '';
                        const backdropUrl = item.backdrop_path 
                            ? `https://image.tmdb.org/t/p/original${item.backdrop_path}`
                            : posterUrl;

                        results.push({
                            type: mType,
                            title: title,
                            year: year,
                            tmdbId: item.id,
                            overview: item.overview || '',
                            rating: item.vote_average ? parseFloat(item.vote_average.toFixed(1)) : 7.5,
                            runtime: mType === 'movie' ? 'Feature' : 'Series',
                            certification: 'PG-13',
                            genres: ['Drama', 'Action'],
                            remotePoster: posterUrl,
                            backdrop: backdropUrl,
                            raw: item
                        });
                    });
                }
            } catch (tmdbErr) {
                console.log('[Seerr Search] TMDB search error:', tmdbErr.message);
            }

            // 2. Search in curated modern cache
            const localMatches = CURATED_MEDIA.filter(item =>
                (item.title && item.title.toLowerCase().includes(queryLow)) ||
                (item.overview && item.overview.toLowerCase().includes(queryLow))
            );
            localMatches.forEach(lm => {
                const exists = results.some(r => 
                    (r.tmdbId && r.tmdbId === lm.tmdbId) || 
                    (r.title && r.title.toLowerCase() === lm.title.toLowerCase())
                );
                if (!exists) {
                    results.unshift(lm);
                }
            });

            // 2. Query Radarr live lookup
            if (keys.radarr) {
                try {
                    const movies = await fetchJson(`http://127.0.0.1:7878/api/v3/movie/lookup?term=${encodeURIComponent(q)}&apiKey=${keys.radarr}`, { timeout: 3500 });
                    if (Array.isArray(movies)) {
                        movies.slice(0, 12).forEach(m => {
                            const exists = results.some(r => (r.tmdbId && r.tmdbId === m.tmdbId) || (r.title && r.title.toLowerCase() === (m.title || '').toLowerCase()));
                            if (!exists) {
                                results.push({
                                    type: 'movie',
                                    title: m.title,
                                    year: m.year,
                                    tmdbId: m.tmdbId,
                                    overview: m.overview,
                                    rating: m.ratings ? (m.ratings.value || (m.ratings.tmdb ? m.ratings.tmdb.value : 8.0)) : 8.0,
                                    runtime: m.runtime ? (Math.floor(m.runtime / 60) + 'h ' + (m.runtime % 60) + 'm') : 'Feature',
                                    certification: m.certification || 'PG-13',
                                    genres: m.genres || ['Action', 'Drama'],
                                    remotePoster: extractImage(m.images, 'poster'),
                                    backdrop: extractImage(m.images, 'fanart') || extractImage(m.images, 'poster'),
                                    raw: m
                                });
                            }
                        });
                    }
                } catch (e) {}
            }

            // 3. Query Sonarr live lookup
            if (keys.sonarr) {
                try {
                    const series = await fetchJson(`http://127.0.0.1:8989/api/v3/series/lookup?term=${encodeURIComponent(q)}&apiKey=${keys.sonarr}`, { timeout: 3500 });
                    if (Array.isArray(series)) {
                        series.slice(0, 12).forEach(s => {
                            const exists = results.some(r => (r.tvdbId && r.tvdbId === s.tvdbId) || (r.title && r.title.toLowerCase() === (s.title || '').toLowerCase()));
                            if (!exists) {
                                results.push({
                                    type: 'tv',
                                    title: s.title,
                                    year: s.year,
                                    tvdbId: s.tvdbId,
                                    overview: s.overview,
                                    rating: s.ratings ? (s.ratings.value || 8.2) : 8.2,
                                    runtime: 'Series',
                                    certification: s.certification || 'TV-MA',
                                    genres: s.genres || ['Drama'],
                                    remotePoster: extractImage(s.images, 'poster'),
                                    backdrop: extractImage(s.images, 'fanart') || extractImage(s.images, 'poster'),
                                    seasons: s.seasons || [],
                                    raw: s
                                });
                            }
                        });
                    }
                } catch (e) {}
            }
        }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(results));
        return;
    }

    // 10b. Request Count & History
    if (pathname === '/api/v1/request/count') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ total: requestHistory.length, pending: 0, approved: requestHistory.length }));
        return;
    }

    if (pathname === '/api/v1/request' && req.method === 'GET') {
        const pendingCount = requestHistory.filter(r => r.status === 'pending').length;
        const approvedCount = requestHistory.filter(r => r.status === 'approved' || r.status === 'dispatched').length;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ 
            results: requestHistory, 
            total: requestHistory.length,
            pending: pendingCount,
            approved: approvedCount
        }));
        return;
    }

    // 10c. Dispatch Pending Requests
    if (pathname === '/api/v1/request/dispatch-pending' && req.method === 'POST') {
        const stats = await dispatchPendingRequests();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ 
            success: true, 
            dispatched: stats.dispatched, 
            pending: stats.pending, 
            message: `Dispatched ${stats.dispatched} pending request(s) (${stats.pending} still pending).` 
        }));
        return;
    }

    // 11. Request Media (POST)
    if (pathname === '/api/v1/request' && req.method === 'POST') {
        let bodyStr = '';
        req.on('data', c => bodyStr += c);
        req.on('end', async () => {
            try {
                const body = JSON.parse(bodyStr || '{}');
                const keys = getStackKeys();
                const type = (body.type || body.mediaType || (body.item && body.item.type) || 'movie').toLowerCase();
                const item = body.item || body;
                const mediaTitle = item.title || item.name || body.title || 'Untitled';
                const mediaYear = item.year || body.year || 0;

                if (type === 'movie') {
                    const tmdbIdNum = parseInt(item.tmdbId || item.id || body.tmdbId || body.mediaId || 0, 10);
                    if (!tmdbIdNum || isNaN(tmdbIdNum)) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false, message: 'TMDB ID is required for movie request' }));
                        return;
                    }
                    const normTitle = mediaTitle.trim().toLowerCase();
                    const cleanSlug = normTitle.replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');

                    // 1. DISK CHECK: If file/folder already exists in FishbowlMedia/Movies or /sdcard, do not download again
                    if (isMovieInDiskLibrary(mediaTitle, mediaYear)) {
                        recordRequest({
                            title: mediaTitle,
                            tmdbId: tmdbIdNum,
                            year: mediaYear,
                            remotePoster: item.remotePoster || (item.raw && item.raw.poster_path ? `https://image.tmdb.org/t/p/w500${item.raw.poster_path}` : ''),
                            backdrop: item.backdrop || ''
                        }, 'movie', 4, 'in_library');

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ 
                            success: true, 
                            alreadyExists: true, 
                            hasFile: true, 
                            message: 'Movie is already downloaded and present in library! Skipping duplicate download.' 
                        }));
                        return;
                    }

                    // 2. CHECK REQUEST HISTORY (Already pending or in library)
                    const histMatch = requestHistory.find(r => 
                        (tmdbIdNum && r.tmdbId === tmdbIdNum) ||
                        (r.title && r.title.trim().toLowerCase() === normTitle)
                    );
                    if (histMatch) {
                        if (histMatch.status === 'pending') {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({
                                success: true,
                                alreadyPending: true,
                                pending: true,
                                message: 'Movie is already queued in Pending Requests! It will auto-dispatch once Radarr turns on.'
                            }));
                            return;
                        } else if (histMatch.status === 'in_library') {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({
                                success: true,
                                alreadyExists: true,
                                hasFile: true,
                                message: 'Movie is already downloaded and present in library! Skipping duplicate download.'
                            }));
                            return;
                        }
                    }

                    let rootPath = body.rootFolderPath || body.rootFolder || '/storage/emulated/0/FishbowlMedia/Movies';
                    let selectedProfileId = parseInt(body.qualityProfileId || body.profileId || 4, 10);
                    if (isNaN(selectedProfileId) || selectedProfileId <= 0) selectedProfileId = 4;

                    // 3. SERVICE ONLINE CHECK
                    const radarrOnline = await probePort(7878, '/api/v3/system/status');
                    if (!radarrOnline || !keys.radarr) {
                        // Radarr is offline: Queue in Pending Requests
                        recordRequest({
                            title: mediaTitle,
                            tmdbId: tmdbIdNum,
                            year: mediaYear,
                            remotePoster: item.remotePoster || (item.raw && item.raw.poster_path ? `https://image.tmdb.org/t/p/w500${item.raw.poster_path}` : ''),
                            backdrop: item.backdrop || ''
                        }, 'movie', selectedProfileId, 'pending', {
                            rootFolderPath: rootPath,
                            titleSlug: (cleanSlug + '-' + tmdbIdNum)
                        });

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({
                            success: true,
                            pending: true,
                            message: 'Radarr is currently offline. Request saved to Pending Requests and will automatically dispatch once Radarr starts.'
                        }));
                        return;
                    }

                    // 4. Radarr is ONLINE: check Radarr existing library before dispatching
                    const existingMovies = await getRadarrExistingMovies(keys.radarr);
                    const existingMatch = existingMovies.find(m => 
                        (tmdbIdNum && m.tmdbId === tmdbIdNum) || 
                        (m.title && m.title.trim().toLowerCase() === normTitle)
                    );

                    if (existingMatch) {
                        recordRequest({
                            title: mediaTitle,
                            tmdbId: tmdbIdNum,
                            year: mediaYear,
                            remotePoster: item.remotePoster || (item.raw && item.raw.poster_path ? `https://image.tmdb.org/t/p/w500${item.raw.poster_path}` : ''),
                            backdrop: item.backdrop || ''
                        }, 'movie', existingMatch.qualityProfileId || selectedProfileId, existingMatch.hasFile ? 'in_library' : 'approved');

                        if (existingMatch.hasFile) {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ 
                                success: true, 
                                alreadyExists: true, 
                                hasFile: true, 
                                message: 'Movie is already downloaded and present in library! Skipping duplicate download.' 
                            }));
                            return;
                        } else {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ 
                                success: true, 
                                alreadyExists: true, 
                                hasFile: false, 
                                message: 'Movie is already requested and currently processing in Radarr.' 
                            }));
                            return;
                        }
                    }

                    const radarrProfiles = await getRadarrQualityProfiles(keys.radarr);
                    const profileValid = radarrProfiles.some(p => p.id === selectedProfileId);
                    if (!profileValid) {
                        const defP = radarrProfiles.find(p => (p.name || '').toLowerCase().includes('1080p')) || radarrProfiles[0];
                        selectedProfileId = defP ? defP.id : 4;
                    }

                    const rootFolders = await getRadarrRootFolders(keys.radarr);
                    if (rootFolders.length > 0 && !rootFolders.includes(rootPath)) {
                        rootPath = rootFolders[0];
                    }

                    const moviePayload = {
                        title: mediaTitle,
                        tmdbId: tmdbIdNum,
                        year: parseInt(mediaYear, 10) || undefined,
                        qualityProfileId: selectedProfileId,
                        rootFolderPath: rootPath,
                        monitored: true,
                        titleSlug: (item.raw && item.raw.titleSlug) ? item.raw.titleSlug : (cleanSlug + '-' + tmdbIdNum),
                        addOptions: { searchForMovie: true }
                    };

                    if (item.raw && Array.isArray(item.raw.images) && item.raw.images.length > 0) {
                        moviePayload.images = item.raw.images;
                    } else {
                        const imgs = [];
                        if (item.remotePoster) imgs.push({ coverType: 'poster', url: item.remotePoster, remoteUrl: item.remotePoster });
                        if (item.backdrop) imgs.push({ coverType: 'fanart', url: item.backdrop, remoteUrl: item.backdrop });
                        if (imgs.length > 0) moviePayload.images = imgs;
                    }

                    try {
                        const radarrRes = await fetchJson(`http://127.0.0.1:7878/api/v3/movie?apiKey=${keys.radarr}`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Api-Key': keys.radarr
                            },
                            body: moviePayload
                        });

                        recordRequest({
                            title: mediaTitle,
                            tmdbId: tmdbIdNum,
                            year: moviePayload.year,
                            remotePoster: item.remotePoster || (item.raw && item.raw.poster_path ? `https://image.tmdb.org/t/p/w500${item.raw.poster_path}` : ''),
                            backdrop: item.backdrop || ''
                        }, 'movie', selectedProfileId, 'approved');

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: true, result: radarrRes }));
                        return;
                    } catch (err) {
                        const msg = (err.message || '').toLowerCase();
                        if (msg.includes('already exists') || msg.includes('movieexistsvalidator')) {
                            recordRequest({
                                title: mediaTitle,
                                tmdbId: tmdbIdNum,
                                year: moviePayload.year,
                                remotePoster: item.remotePoster || '',
                                backdrop: item.backdrop || ''
                            }, 'movie', selectedProfileId, 'approved');

                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ success: true, alreadyExists: true, message: 'Already exists in library!' }));
                            return;
                        }
                        if (msg.includes('econnrefused') || msg.includes('socket') || msg.includes('timeout')) {
                            recordRequest({
                                title: mediaTitle,
                                tmdbId: tmdbIdNum,
                                year: moviePayload.year,
                                remotePoster: item.remotePoster || '',
                                backdrop: item.backdrop || ''
                            }, 'movie', selectedProfileId, 'pending', {
                                rootFolderPath: rootPath,
                                titleSlug: moviePayload.titleSlug
                            });

                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ 
                                success: true, 
                                pending: true, 
                                message: 'Radarr connection lost. Request saved to Pending Requests and will auto-dispatch when Radarr is reachable.' 
                            }));
                            return;
                        }
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false, message: 'Radarr rejected request: ' + err.message }));
                        return;
                    }
                } else if (type === 'tv') {
                    const tvdbIdNum = parseInt(item.tvdbId || item.id || body.tvdbId || body.mediaId || 0, 10);
                    if (!tvdbIdNum || isNaN(tvdbIdNum)) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false, message: 'TVDB ID is required for series request' }));
                        return;
                    }
                    const normTitle = mediaTitle.trim().toLowerCase();
                    const cleanSlug = normTitle.replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');

                    // 1. DISK CHECK: If file/folder already exists in FishbowlMedia/TV or /sdcard
                    if (isTvInDiskLibrary(mediaTitle, mediaYear)) {
                        recordRequest({
                            title: mediaTitle,
                            tvdbId: tvdbIdNum,
                            year: mediaYear,
                            remotePoster: item.remotePoster || '',
                            backdrop: item.backdrop || ''
                        }, 'tv', 4, 'in_library');

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ 
                            success: true, 
                            alreadyExists: true, 
                            hasFile: true, 
                            message: 'Series is already downloaded and present in library! Skipping duplicate download.' 
                        }));
                        return;
                    }

                    // 2. CHECK REQUEST HISTORY (Already pending or in library)
                    const histMatch = requestHistory.find(r => 
                        (tvdbIdNum && r.tvdbId === tvdbIdNum) ||
                        (r.title && r.title.trim().toLowerCase() === normTitle)
                    );
                    if (histMatch) {
                        if (histMatch.status === 'pending') {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({
                                success: true,
                                alreadyPending: true,
                                pending: true,
                                message: 'Series is already queued in Pending Requests! It will auto-dispatch once Sonarr turns on.'
                            }));
                            return;
                        } else if (histMatch.status === 'in_library') {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({
                                success: true,
                                alreadyExists: true,
                                hasFile: true,
                                message: 'Series is already in your library!'
                            }));
                            return;
                        }
                    }

                    let rootPath = body.rootFolderPath || body.rootFolder || '/storage/emulated/0/FishbowlMedia/TV';
                    let selectedProfileId = parseInt(body.qualityProfileId || body.profileId || 4, 10);
                    if (isNaN(selectedProfileId) || selectedProfileId <= 0) selectedProfileId = 4;

                    let seasonsConfig = [];
                    if (Array.isArray(body.seasons) && body.seasons.length > 0) {
                        seasonsConfig = body.seasons.map(num => ({ seasonNumber: num, monitored: true }));
                    } else if (Array.isArray(item.seasons)) {
                        seasonsConfig = item.seasons.map(s => ({ seasonNumber: s.seasonNumber, monitored: true }));
                    }

                    // 3. SERVICE ONLINE CHECK
                    const sonarrOnline = await probePort(8989, '/api/v3/system/status');
                    if (!sonarrOnline || !keys.sonarr) {
                        // Sonarr is offline: Queue in Pending Requests
                        recordRequest({
                            title: mediaTitle,
                            tvdbId: tvdbIdNum,
                            year: mediaYear,
                            remotePoster: item.remotePoster || '',
                            backdrop: item.backdrop || ''
                        }, 'tv', selectedProfileId, 'pending', {
                            rootFolderPath: rootPath,
                            titleSlug: (cleanSlug + '-' + tvdbIdNum),
                            seasons: seasonsConfig
                        });

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({
                            success: true,
                            pending: true,
                            message: 'Sonarr is currently offline. Request saved to Pending Requests and will automatically dispatch once Sonarr starts.'
                        }));
                        return;
                    }

                    // 4. Sonarr is ONLINE: check Sonarr existing library before dispatching
                    const existingSeries = await getSonarrExistingSeries(keys.sonarr);
                    const existingMatch = existingSeries.find(s => 
                        (tvdbIdNum && s.tvdbId === tvdbIdNum) || 
                        (s.title && s.title.trim().toLowerCase() === normTitle)
                    );

                    if (existingMatch) {
                        const isComplete = existingMatch.percentOfEpisodes === 100 || 
                                           (existingMatch.statistics && existingMatch.statistics.percentOfEpisodes === 100);
                        recordRequest({
                            title: mediaTitle,
                            tvdbId: tvdbIdNum,
                            year: mediaYear,
                            remotePoster: item.remotePoster || '',
                            backdrop: item.backdrop || ''
                        }, 'tv', existingMatch.qualityProfileId || selectedProfileId, isComplete ? 'in_library' : 'approved');

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ 
                            success: true, 
                            alreadyExists: true, 
                            hasFile: isComplete, 
                            message: isComplete ? 
                                'Series is already downloaded and present in library! Skipping duplicate download.' : 
                                'Series is already requested and monitored in Sonarr.' 
                        }));
                        return;
                    }

                    const rootFolders = await getSonarrRootFolders(keys.sonarr);
                    if (rootFolders.length > 0 && !rootFolders.includes(rootPath)) {
                        rootPath = rootFolders[0];
                    }

                    const sonarrProfiles = await getSonarrQualityProfiles(keys.sonarr);
                    const profileValid = sonarrProfiles.some(p => p.id === selectedProfileId);
                    if (!profileValid) {
                        const defP = sonarrProfiles.find(p => (p.name || '').toLowerCase().includes('1080p')) || sonarrProfiles[0];
                        selectedProfileId = defP ? defP.id : 4;
                    }

                    const seriesPayload = {
                        title: mediaTitle,
                        tvdbId: tvdbIdNum,
                        year: parseInt(mediaYear, 10) || undefined,
                        qualityProfileId: selectedProfileId,
                        languageProfileId: 1,
                        rootFolderPath: rootPath,
                        monitored: true,
                        titleSlug: (item.raw && item.raw.titleSlug) ? item.raw.titleSlug : (cleanSlug + '-' + tvdbIdNum),
                        seasons: seasonsConfig,
                        addOptions: { searchForMissingEpisodes: true }
                    };

                    if (item.raw && Array.isArray(item.raw.images) && item.raw.images.length > 0) {
                        seriesPayload.images = item.raw.images;
                    } else {
                        const imgs = [];
                        if (item.remotePoster) imgs.push({ coverType: 'poster', url: item.remotePoster, remoteUrl: item.remotePoster });
                        if (item.backdrop) imgs.push({ coverType: 'fanart', url: item.backdrop, remoteUrl: item.backdrop });
                        if (imgs.length > 0) seriesPayload.images = imgs;
                    }

                    try {
                        const sonarrRes = await fetchJson(`http://127.0.0.1:8989/api/v3/series?apiKey=${keys.sonarr}`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Api-Key': keys.sonarr
                            },
                            body: seriesPayload
                        });

                        recordRequest({
                            title: mediaTitle,
                            tvdbId: tvdbIdNum,
                            year: seriesPayload.year,
                            remotePoster: item.remotePoster || '',
                            backdrop: item.backdrop || ''
                        }, 'tv', selectedProfileId, 'approved');

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: true, result: sonarrRes }));
                        return;
                    } catch (err) {
                        const msg = (err.message || '').toLowerCase();
                        if (msg.includes('already exists') || msg.includes('seriesexistsvalidator')) {
                            recordRequest({
                                title: mediaTitle,
                                tvdbId: tvdbIdNum,
                                year: seriesPayload.year,
                                remotePoster: item.remotePoster || '',
                                backdrop: item.backdrop || ''
                            }, 'tv', selectedProfileId, 'approved');

                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ success: true, alreadyExists: true, message: 'Already exists in library!' }));
                            return;
                        }
                        if (msg.includes('econnrefused') || msg.includes('socket') || msg.includes('timeout')) {
                            recordRequest({
                                title: mediaTitle,
                                tvdbId: tvdbIdNum,
                                year: seriesPayload.year,
                                remotePoster: item.remotePoster || '',
                                backdrop: item.backdrop || ''
                            }, 'tv', selectedProfileId, 'pending', {
                                rootFolderPath: rootPath,
                                titleSlug: seriesPayload.titleSlug,
                                seasons: seasonsConfig
                            });

                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ 
                                success: true, 
                                pending: true, 
                                message: 'Sonarr connection lost. Request saved to Pending Requests and will auto-dispatch when Sonarr is reachable.' 
                            }));
                            return;
                        }
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false, message: 'Sonarr rejected request: ' + err.message }));
                        return;
                    }
                }
            } catch (err) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: err.message }));
            }
        });
        return;
    }

    // 11b. Cancel Request (POST)
    if (pathname === '/api/v1/request/cancel' && req.method === 'POST') {
        let bodyStr = '';
        req.on('data', c => bodyStr += c);
        req.on('end', async () => {
            try {
                const body = JSON.parse(bodyStr);
                const keys = getStackKeys();
                const type = (body.type || body.mediaType || 'movie').toLowerCase();
                const title = body.title || (body.item && body.item.title) || '';
                const tmdbId = body.tmdbId || (body.item && body.item.tmdbId);
                const tvdbId = body.tvdbId || (body.item && body.item.tvdbId);

                let deletedFromService = false;
                if (type === 'movie' && keys.radarr) {
                    deletedFromService = await cancelRadarrMovie(tmdbId, title, keys.radarr);
                } else if (type === 'tv' && keys.sonarr) {
                    deletedFromService = await cancelSonarrSeries(tvdbId, title, keys.sonarr);
                }

                // Remove from requestHistory
                requestHistory = requestHistory.filter(r => {
                    if (body.id && r.id === body.id) return false;
                    if (title && r.title && r.title.toLowerCase() === title.toLowerCase()) return false;
                    if (tmdbId && r.tmdbId === tmdbId) return false;
                    if (tvdbId && r.tvdbId === tvdbId) return false;
                    return true;
                });
                try {
                    fs.writeFileSync(REQUESTS_FILE, JSON.stringify(requestHistory, null, 2), 'utf8');
                } catch (e) {}

                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    success: true,
                    unlinkedFromAutomation: deletedFromService,
                    message: 'Request cancelled for "' + title + '"'
                }));
            } catch (err) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: err.message }));
            }
        });
        return;
    }

    // 12. Active BitTorrent Downloads (Transmission)
    if (pathname === '/api/v1/downloads') {
        const torrents = await getTransmissionTorrents();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(torrents));
        return;
    }

    // 13. Transmission Controls (Pause / Resume / Delete)
    if (pathname === '/api/v1/downloads/pause' && req.method === 'POST') {
        let bodyStr = '';
        req.on('data', c => bodyStr += c);
        req.on('end', async () => {
            try {
                const b = JSON.parse(bodyStr);
                const ok = await pauseTransmissionTorrent(b.id);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: ok }));
            } catch (e) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: e.message }));
            }
        });
        return;
    }

    if (pathname === '/api/v1/downloads/resume' && req.method === 'POST') {
        let bodyStr = '';
        req.on('data', c => bodyStr += c);
        req.on('end', async () => {
            try {
                const b = JSON.parse(bodyStr);
                const ok = await resumeTransmissionTorrent(b.id);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: ok }));
            } catch (e) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: e.message }));
            }
        });
        return;
    }

    if (pathname === '/api/v1/downloads/delete' && req.method === 'POST') {
        let bodyStr = '';
        req.on('data', c => bodyStr += c);
        req.on('end', async () => {
            try {
                const b = JSON.parse(bodyStr);
                const ok = await removeTransmissionTorrent(b.id, b.deleteData !== false);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: ok }));
            } catch (e) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ success: false, message: e.message }));
            }
        });
        return;
    }

    // 14. Root SPA
    if (pathname === '/' || pathname === '/index.html') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(getAppHtml());
        return;
    }

    // 404
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`[Seerr] Listening on http://0.0.0.0:${PORT}`);
    console.log(`[Seerr] Config directory: ${CONFIG_DIR}`);
});
