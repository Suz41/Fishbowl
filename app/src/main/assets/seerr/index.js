// Fishbowl Seerr (Overseerr / Jellyseerr) Request & Discovery Engine
// Native Node.js Bionic Service on Port 5055
// High-Fidelity Desktop & Mobile Responsive Control Plane & Comprehensive REST API

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const url = require('url');

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

        const req = client.request({
            protocol: parsed.protocol,
            hostname: parsed.hostname,
            port: parsed.port || (isHttps ? 443 : 80),
            path: parsed.path,
            method: options.method || 'GET',
            headers: headers,
            timeout: options.timeout || 5000
        }, (res) => {
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
    // Top Movies (2024-2026)
    {
        type: 'movie',
        title: 'Dune: Part Two',
        year: 2024,
        tmdbId: 693134,
        overview: 'Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family. Facing a choice between the love of his life and the fate of the known universe, he endeavors to prevent a terrible future only he can foresee.',
        rating: 8.6,
        runtime: '2h 46m',
        certification: 'PG-13',
        genres: ['Science Fiction', 'Adventure'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/xOMo8BRK7PfcJv9JCnx7s5200SV.jpg'
    },
    {
        type: 'movie',
        title: 'Deadpool & Wolverine',
        year: 2024,
        tmdbId: 533535,
        overview: 'A listless Wade Wilson toils away in civilian life with his days as the morally flexible mercenary behind him. But when his homeworld faces an existential threat, Wade must reluctantly suit-up again with an even more reluctant Wolverine.',
        rating: 7.7,
        runtime: '2h 8m',
        certification: 'R',
        genres: ['Action', 'Comedy', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/yDHYTfA3R0jFYba16jBB1jv8uqC.jpg'
    },
    {
        type: 'movie',
        title: 'Gladiator II',
        year: 2024,
        tmdbId: 558449,
        overview: 'Years after witnessing the death of the revered hero Maximus at the hands of his uncle, Lucius must enter the Colosseum after his home is conquered by the tyrannical Emperors who now lead Rome with an iron fist.',
        rating: 7.3,
        runtime: '2h 28m',
        certification: 'R',
        genres: ['Action', 'Adventure', 'Drama'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/euYIwmqkmz95mnXvufEmbL69mZ9.jpg'
    },
    {
        type: 'movie',
        title: 'Wicked',
        year: 2024,
        tmdbId: 402431,
        overview: 'Elphaba, an ostracized but gifted young woman, and Glinda, an exceptionally popular young woman, become unlikely friends in the magical Land of Oz. Following an encounter with the Wizard, their lives take very different paths.',
        rating: 7.8,
        runtime: '2h 40m',
        certification: 'PG',
        genres: ['Drama', 'Fantasy', 'Romance'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/xDGbZ0JJ3mYaGKy4Nzd9Kph6M9L.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/uVl9iE2VDUuXqrhB72ASt5zYWUd.jpg'
    },
    {
        type: 'movie',
        title: 'Thunderbolts*',
        year: 2025,
        tmdbId: 986056,
        overview: 'An irreverent team-up of Marvel Comics antiheroes assembled by Valentina Allegra de Fontaine to execute covert missions.',
        rating: 8.4,
        runtime: '2h 15m',
        certification: 'PG-13',
        genres: ['Action', 'Adventure', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/hqcexYHbiTBfDIdDWxrxPtVndBX.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/rthMuZfFv4fqEU4JVbgSW9wQ8rs.jpg'
    },
    {
        type: 'movie',
        title: 'Alien: Romulus',
        year: 2024,
        tmdbId: 945961,
        overview: 'While scavenging the deep ends of a derelict space station, a group of young space colonizers come face to face with the most terrifying life form in the universe.',
        rating: 7.2,
        runtime: '1h 59m',
        certification: 'R',
        genres: ['Horror', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/original/2uSWRTtCG336nuBiG8jOTEUKSy8.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/iYqSQaWDttQIQzsxg9xHyg0bttG.jpg'
    },
    {
        type: 'movie',
        title: 'Beetlejuice Beetlejuice',
        year: 2024,
        tmdbId: 917496,
        overview: 'Three generations of the Deetz family return home to Winter River after an unexpected family tragedy. Still haunted by Beetlejuice, Lydia\'s life is turned upside down when her rebellious teenage daughter discovers the portal to the Afterlife.',
        rating: 7.1,
        runtime: '1h 45m',
        certification: 'PG-13',
        genres: ['Comedy', 'Fantasy', 'Horror'],
        remotePoster: 'https://image.tmdb.org/t/p/original/kKgQzkUCnQmeTPkyIwHly2t6ZFI.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/kF8ljC7Y4p1UsmKBi2LxelZpqw.jpg'
    },
    {
        type: 'movie',
        title: 'Inside Out 2',
        year: 2024,
        tmdbId: 1022789,
        overview: 'Teenager Riley\'s mind headquarters is undergoing a sudden demolition to make room for something entirely unexpected: new Emotions! Joy, Sadness, Anger, Fear and Disgust aren\'t sure how to feel when Anxiety shows up.',
        rating: 7.6,
        runtime: '1h 36m',
        certification: 'PG',
        genres: ['Animation', 'Family', 'Adventure', 'Comedy'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/vpnVM9B6NMmQpWeZvzLvDESb2QY.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/xg27NrXi7VXCGUr7MG75UqLl6Vg.jpg'
    },
    {
        type: 'movie',
        title: 'Moana 2',
        year: 2024,
        tmdbId: 1241982,
        overview: 'After receiving an unexpected call from her wayfinding ancestors, Moana journeys alongside Maui and an unlikely crew to the far seas of Oceania into dangerous, long-lost waters.',
        rating: 7.0,
        runtime: '1h 40m',
        certification: 'PG',
        genres: ['Animation', 'Adventure', 'Family', 'Comedy'],
        remotePoster: 'https://image.tmdb.org/t/p/original/aLVkiINlIeCkcZIzb7XHzPYgO6L.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/vYqt6kb4lcF8wwqsMMaULkP9OEn.jpg'
    },
    {
        type: 'movie',
        title: 'The Substance',
        year: 2024,
        tmdbId: 933260,
        overview: 'A fading celebrity takes a black-market cell-replicating substance that temporarily creates a younger, better version of herself, leading to terrifying consequences.',
        rating: 7.4,
        runtime: '2h 21m',
        certification: 'R',
        genres: ['Horror', 'Drama', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/original/lqoMzCcZYEFK729d6qzt349fB4o.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/bVSOgrxasVJF6V71T7v2KfBRSzu.jpg'
    },
    {
        type: 'movie',
        title: 'Nosferatu',
        year: 2024,
        tmdbId: 426063,
        overview: 'A gothic tale of obsession between a haunted young woman and the terrifying vampire infatuated with her, causing untold horror in its wake.',
        rating: 7.1,
        runtime: '2h 12m',
        certification: 'R',
        genres: ['Horror', 'Fantasy', 'Mystery'],
        remotePoster: 'https://image.tmdb.org/t/p/original/5qGIxdEO841C0tdY8vOdLoRVrr0.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/gprjiZWY43vxSKngMha1wfb5TGG.jpg'
    },
    {
        type: 'movie',
        title: 'Furiosa: A Mad Max Saga',
        year: 2024,
        tmdbId: 786892,
        overview: 'As the world fell, young Furiosa is snatched from the Green Place of Many Mothers and falls into the hands of a great Biker Horde led by the Warlord Dementus.',
        rating: 7.6,
        runtime: '2h 28m',
        certification: 'R',
        genres: ['Action', 'Adventure', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/original/iADOJ8Zymht2JPMoy3R7xceZprc.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/raph7qjAGTMXaIjVxt6ZDSXRzUr.jpg'
    },
    {
        type: 'movie',
        title: 'Oppenheimer',
        year: 2023,
        tmdbId: 872585,
        overview: 'The story of J. Robert Oppenheimer\'s role in the development of the atomic bomb during World War II.',
        rating: 8.1,
        runtime: '3h 0m',
        certification: 'R',
        genres: ['Drama', 'History'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/fm6K9vYI7vdENBhqlF4K1a3aH33.jpg'
    },
    {
        type: 'movie',
        title: 'Avatar: Fire and Ash',
        year: 2025,
        tmdbId: 83533,
        overview: 'The third chapter in the Avatar saga explores the Ash People, a volcanic clan of Na\'vi, and pushes the boundary of Pandora\'s civil conflicts.',
        rating: 8.5,
        runtime: '3h 10m',
        certification: 'PG-13',
        genres: ['Action', 'Adventure', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/vL5LR6WdxWPquCc8a46ixyeYMm7.jpg'
    },
    {
        type: 'movie',
        title: 'Captain America: Brave New World',
        year: 2025,
        tmdbId: 822119,
        overview: 'Sam Wilson finds himself in the middle of an international incident following a meeting with President Thaddeus Ross, uncovering a global conspiracy.',
        rating: 8.0,
        runtime: '2h 18m',
        certification: 'PG-13',
        genres: ['Action', 'Science Fiction', 'Thriller'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/7h6r93ooR2x77iMZZv1C5zWpB5o.jpg'
    },
    {
        type: 'movie',
        title: 'Superman',
        year: 2025,
        tmdbId: 1061474,
        overview: 'Clark Kent reconciles his Kryptonian heritage with his human upbringing in a world that views kindness as old-fashioned.',
        rating: 8.2,
        runtime: '2h 25m',
        certification: 'PG-13',
        genres: ['Action', 'Adventure', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/xJHokMbljvjADYdit5fK5VQsXEG.jpg'
    },
    {
        type: 'movie',
        title: 'Mission: Impossible - The Final Reckoning',
        year: 2025,
        tmdbId: 575265,
        overview: 'Ethan Hunt and his IMF team track down the rogue Entity, facing a deadly race against time to determine the fate of humanity.',
        rating: 8.3,
        runtime: '2h 45m',
        certification: 'PG-13',
        genres: ['Action', 'Adventure', 'Thriller'],
        remotePoster: 'https://image.tmdb.org/t/p/original/iKPsC9EFUafRP9SrUznI61getVP.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/538U9snNc2fpnOmYXAPUh3zn31H.jpg'
    },
    {
        type: 'movie',
        title: 'Avatar: The Way of Water',
        year: 2022,
        tmdbId: 76600,
        overview: 'Set more than a decade after the events of the first film, Jake Sully and Neytiri have formed a family and are doing everything to stay together.',
        rating: 7.6,
        runtime: '3h 12m',
        certification: 'PG-13',
        genres: ['Science Fiction', 'Action', 'Adventure'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/s16H6tpK2utvwDtzZ8Qy4qm5Emw.jpg'
    },
    {
        type: 'movie',
        title: 'The Dark Knight',
        year: 2008,
        tmdbId: 155,
        overview: 'Batman raises the stakes in his war on crime. With the help of Lt. Jim Gordon and District Attorney Harvey Dent, Batman sets out to dismantle the remaining criminal organizations that plague the streets.',
        rating: 9.0,
        runtime: '2h 32m',
        certification: 'PG-13',
        genres: ['Drama', 'Action', 'Crime'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/nMKdUUepR0i5zn0y1T4CsSB5chy.jpg'
    },
    {
        type: 'movie',
        title: 'Interstellar',
        year: 2014,
        tmdbId: 157336,
        overview: 'The adventures of a group of explorers who make use of a newly discovered wormhole to surpass the limitations on human space travel.',
        rating: 8.7,
        runtime: '2h 49m',
        certification: 'PG-13',
        genres: ['Science Fiction', 'Adventure', 'Drama'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/xJHokMbljvjADYdit5fK5VQsXEG.jpg'
    },
    {
        type: 'movie',
        title: 'The Martian',
        year: 2015,
        tmdbId: 286217,
        overview: 'During a manned mission to Mars, Astronaut Mark Watney is presumed dead after a fierce storm and left behind by his crew.',
        rating: 8.0,
        runtime: '2h 24m',
        certification: 'PG-13',
        genres: ['Drama', 'Adventure', 'Science Fiction'],
        remotePoster: 'https://image.tmdb.org/t/p/original/5BHuvQ6p9kfc091Z8RiFNhCwL4b.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/lzMS0CI3FLQYC5EgJoWeIaEt0lm.jpg'
    },

    // Top TV Shows (With Full Seasons & Episode Metadata)
    {
        type: 'tv',
        title: 'The Last of Us',
        year: 2023,
        tvdbId: 392256,
        overview: 'Twenty years after modern civilization has been destroyed, Joel, a hardened survivor, is hired to smuggle Ellie, a 14-year-old girl, out of an oppressive quarantine zone.',
        rating: 8.8,
        certification: 'TV-MA',
        genres: ['Drama', 'Science Fiction', 'Action', 'Horror'],
        remotePoster: 'https://artworks.thetvdb.com/banners/v4/series/392256/posters/6362e8b41ca10.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/v4/series/392256/backgrounds/639b60a2a0921.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 9, monitored: true },
            { seasonNumber: 2, episodeCount: 7, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Shōgun',
        year: 2024,
        tvdbId: 396827,
        overview: 'In Japan in the year 1600, Lord Yoshii Toranaga is fighting for his life as his enemies on the Council of Regents unite against him.',
        rating: 8.7,
        certification: 'TV-MA',
        genres: ['Drama', 'War', 'History'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/7O4iVfOMQmdCSxhOg1WnzG1AgYT.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/5zmiBoMze0qXi7Ac06xiuoCunxX.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 10, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Fallout',
        year: 2024,
        tvdbId: 416744,
        overview: 'In a future, post-apocalyptic Los Angeles brought about by nuclear decimation, citizens must live in underground bunkers to protect themselves from radiation, mutants and bandits.',
        rating: 8.5,
        certification: 'TV-MA',
        genres: ['Science Fiction', 'Action', 'Adventure'],
        remotePoster: 'https://artworks.thetvdb.com/banners/v4/series/416744/posters/656c96bea0c11.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/v4/series/416744/backgrounds/694060bceeb9e.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 8, monitored: true },
            { seasonNumber: 2, episodeCount: 8, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Severance',
        year: 2022,
        tvdbId: 371980,
        overview: 'Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.',
        rating: 8.7,
        certification: 'TV-MA',
        genres: ['Science Fiction', 'Mystery', 'Drama', 'Thriller'],
        remotePoster: 'https://artworks.thetvdb.com/banners/v4/series/371980/posters/621096b26f0e2.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/v4/series/371980/backgrounds/61bdee7c62d99.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 9, monitored: true },
            { seasonNumber: 2, episodeCount: 10, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'House of the Dragon',
        year: 2022,
        tvdbId: 371572,
        overview: 'The prequel series follows the Dance of the Dragons, the Targaryen civil war that took place nearly two centuries before the events of Game of Thrones.',
        rating: 8.4,
        certification: 'TV-MA',
        genres: ['Drama', 'Action', 'Fantasy'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/7QMsOTMUswlwxJP0rTTZfmz2tX2.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/etjA69oh0nil3HBoNVsvGIYEm0K.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 10, monitored: true },
            { seasonNumber: 2, episodeCount: 8, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Stranger Things',
        year: 2016,
        tvdbId: 305288,
        overview: 'When a young boy vanishes, a small town uncovers a mystery involving secret experiments, terrifying supernatural forces and one strange little girl.',
        rating: 8.7,
        certification: 'TV-14',
        genres: ['Science Fiction', 'Mystery', 'Drama', 'Horror'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/56v2KjBlU4XaOv9rVYEQypROD7P.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 8, monitored: true },
            { seasonNumber: 2, episodeCount: 9, monitored: true },
            { seasonNumber: 3, episodeCount: 8, monitored: true },
            { seasonNumber: 4, episodeCount: 9, monitored: true },
            { seasonNumber: 5, episodeCount: 8, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'The Boys',
        year: 2019,
        tvdbId: 355567,
        overview: 'A fun and irreverent take on what happens when superheroes, who are as popular as celebrities, abuse their superpowers rather than use them for good.',
        rating: 8.7,
        certification: 'TV-MA',
        genres: ['Action', 'Comedy', 'Science Fiction'],
        remotePoster: 'https://artworks.thetvdb.com/banners/posters/5c5c402b075cc.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/fanart/original/5d120186c0c5f.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 8, monitored: true },
            { seasonNumber: 2, episodeCount: 8, monitored: true },
            { seasonNumber: 3, episodeCount: 8, monitored: true },
            { seasonNumber: 4, episodeCount: 8, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Squid Game',
        year: 2021,
        tvdbId: 383275,
        overview: 'Hundreds of cash-strapped players accept a strange invitation to compete in children\'s games. Inside, a tempting prize awaits with deadly high stakes.',
        rating: 8.0,
        certification: 'TV-MA',
        genres: ['Action', 'Mystery', 'Drama', 'Thriller'],
        remotePoster: 'https://artworks.thetvdb.com/banners/v4/series/383275/posters/6151351c5420c.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/v4/series/383275/backgrounds/61131e54d37c6.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 9, monitored: true },
            { seasonNumber: 2, episodeCount: 6, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'The Bear',
        year: 2022,
        tvdbId: 403294,
        overview: 'A young fine-dining chef comes home to Chicago to run his family Italian beef sandwich shop after a heartbreaking death in his family.',
        rating: 8.6,
        certification: 'TV-MA',
        genres: ['Comedy', 'Drama'],
        remotePoster: 'https://artworks.thetvdb.com/banners/v4/series/403294/posters/69fdd1b91925f.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/v4/series/403294/backgrounds/646fadd1ec73d.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 8, monitored: true },
            { seasonNumber: 2, episodeCount: 10, monitored: true },
            { seasonNumber: 3, episodeCount: 10, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Silo',
        year: 2023,
        tvdbId: 403245,
        overview: 'In a ruined and toxic future, thousands live in a giant silo deep underground. After its sheriff breaks a cardinal rule, engineer Juliette uncovers shocking secrets.',
        rating: 8.1,
        certification: 'TV-MA',
        genres: ['Science Fiction', 'Drama', 'Mystery'],
        remotePoster: 'https://artworks.thetvdb.com/banners/v4/series/403245/posters/64432dea72673.jpg',
        backdrop: 'https://artworks.thetvdb.com/banners/v4/series/403245/backgrounds/640bc23f1fa80.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 10, monitored: true },
            { seasonNumber: 2, episodeCount: 10, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Breaking Bad',
        year: 2008,
        tvdbId: 81189,
        overview: 'Walter White, a New Mexico chemistry teacher, is diagnosed with Stage III cancer and decides to enter the methamphetamine trade to provide for his family.',
        rating: 9.5,
        certification: 'TV-MA',
        genres: ['Crime', 'Drama', 'Thriller'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 7, monitored: true },
            { seasonNumber: 2, episodeCount: 13, monitored: true },
            { seasonNumber: 3, episodeCount: 13, monitored: true },
            { seasonNumber: 4, episodeCount: 13, monitored: true },
            { seasonNumber: 5, episodeCount: 16, monitored: true }
        ]
    },
    {
        type: 'tv',
        title: 'Game of Thrones',
        year: 2011,
        tvdbId: 121361,
        overview: 'Seven noble families fight for control of the mythical land of Westeros. Friction between the houses leads to full-scale war.',
        rating: 9.2,
        certification: 'TV-MA',
        genres: ['Action', 'Adventure', 'Drama', 'Fantasy'],
        remotePoster: 'https://image.tmdb.org/t/p/w500/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg',
        backdrop: 'https://image.tmdb.org/t/p/original/2OMB0ynKlyIenMJWI2Dy9IWT4c.jpg',
        seasons: [
            { seasonNumber: 1, episodeCount: 10, monitored: true },
            { seasonNumber: 2, episodeCount: 10, monitored: true },
            { seasonNumber: 3, episodeCount: 10, monitored: true },
            { seasonNumber: 4, episodeCount: 10, monitored: true },
            { seasonNumber: 5, episodeCount: 10, monitored: true },
            { seasonNumber: 6, episodeCount: 10, monitored: true },
            { seasonNumber: 7, episodeCount: 7, monitored: true },
            { seasonNumber: 8, episodeCount: 6, monitored: true }
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

const HTML_APP = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
<title>Jellyseerr - Discovery & Media Requests</title>
<style>
:root {
  --bg-base: #090A10;
  --bg-surface: #121520;
  --bg-elevated: #1B2030;
  --bg-glass: rgba(18, 21, 32, 0.85);
  --border-subtle: rgba(255, 255, 255, 0.08);
  --border-active: #6366f1;
  --accent-primary: #6366f1;
  --accent-secondary: #00A4DC;
  --accent-emerald: #10b981;
  --accent-glow: rgba(99, 102, 241, 0.35);
  --success: #10b981;
  --warning: #f59e0b;
  --danger: #ef4444;
  --text-main: #f8fafc;
  --text-muted: #94a3b8;
  --text-dim: #64748b;
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
}
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; -webkit-tap-highlight-color: transparent; }
body { background: var(--bg-base); color: var(--text-main); min-height: 100vh; overflow-x: hidden; padding-bottom: 70px; }

/* Overseerr Top Navigation Bar */
header {
  position: sticky; top: 0; z-index: 100;
  background: var(--bg-glass);
  backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
  border-bottom: 1px solid var(--border-subtle);
  padding: 10px 20px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.header-left { display: flex; align-items: center; gap: 24px; }
.header-brand { display: flex; align-items: center; gap: 10px; cursor: pointer; text-decoration: none; color: inherit; }
.logo-icon {
  width: 32px; height: 32px; border-radius: var(--radius-md);
  background: linear-gradient(135deg, #6366f1 0%, #00A4DC 100%);
  display: flex; align-items: center; justify-content: center;
  font-weight: 800; font-size: 15px; color: white;
  box-shadow: 0 4px 12px var(--accent-glow);
}
.brand-title { font-size: 15px; font-weight: 700; letter-spacing: -0.2px; text-transform: uppercase; }

/* Navigation Links (Desktop) */
.nav-links { display: flex; align-items: center; gap: 6px; }
.nav-link-btn {
  background: transparent; border: none; color: var(--text-muted);
  font-size: 13px; font-weight: 600; padding: 7px 12px; border-radius: var(--radius-sm);
  cursor: pointer; transition: all 0.2s ease;
}
.nav-link-btn:hover { color: var(--text-main); background: rgba(255, 255, 255, 0.05); }
.nav-link-btn.active { color: white; background: var(--bg-elevated); border: 1px solid var(--border-subtle); }

/* Stack Health Indicators */
.stack-indicators { display: flex; gap: 6px; align-items: center; }
.stack-pill {
  font-size: 10px; font-weight: 700; padding: 3px 8px; border-radius: 999px;
  background: rgba(255, 255, 255, 0.05); color: var(--text-dim);
  border: 1px solid var(--border-subtle); display: flex; align-items: center; gap: 4px;
}
.stack-pill.online { background: rgba(16, 185, 129, 0.12); color: #34d399; border-color: rgba(16, 185, 129, 0.3); }
.stack-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }

/* Search Bar (Overseerr Wide Search) */
.search-container { max-width: 1280px; margin: 16px auto 8px auto; padding: 0 16px; }
.search-box {
  position: relative; display: flex; align-items: center;
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); padding: 6px 14px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.search-box:focus-within {
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 3px var(--accent-glow);
}
.search-tag { font-size: 11px; font-weight: 700; color: var(--text-dim); margin-right: 10px; letter-spacing: 0.5px; }
.search-input {
  flex: 1; background: transparent; border: none; outline: none;
  font-size: 14px; color: var(--text-main); padding: 8px 0;
}
.search-input::placeholder { color: var(--text-dim); }
.search-clear {
  background: none; border: none; color: var(--text-dim); font-size: 14px;
  cursor: pointer; padding: 4px; display: none;
}

/* Mobile Quick Navigation Chips */
.mobile-nav-chips {
  display: none; overflow-x: auto; padding: 6px 16px; gap: 8px;
  scrollbar-width: none; max-width: 1280px; margin: 0 auto;
}
.mobile-nav-chips::-webkit-scrollbar { display: none; }
@media (max-width: 768px) {
  .nav-links { display: none; }
  .mobile-nav-chips { display: flex; }
}
.chip-btn {
  white-space: nowrap; font-size: 12px; font-weight: 600; padding: 6px 14px;
  border-radius: 999px; background: var(--bg-surface); border: 1px solid var(--border-subtle);
  color: var(--text-muted); cursor: pointer; transition: all 0.2s ease;
}
.chip-btn.active {
  background: var(--accent-primary); color: white; border-color: var(--accent-primary);
  box-shadow: 0 2px 10px var(--accent-glow);
}

/* Overseerr Hero Spotlight Banner */
.hero-spotlight {
  position: relative; max-width: 1280px; margin: 12px auto 24px auto;
  border-radius: var(--radius-lg); overflow: hidden; background: #0c101b;
  border: 1px solid var(--border-subtle); aspect-ratio: 16/9; max-height: 440px;
  display: flex; align-items: flex-end;
}
.hero-backdrop {
  position: absolute; top: 0; left: 0; width: 100%; height: 100%;
  object-fit: cover; object-position: center 25%; filter: brightness(0.7);
}
.hero-gradient {
  position: absolute; inset: 0;
  background: linear-gradient(180deg, rgba(9,10,16,0.1) 0%, rgba(9,10,16,0.7) 60%, var(--bg-base) 100%);
}
.hero-content {
  position: relative; z-index: 2; padding: 24px 32px; max-width: 750px;
}
.hero-tags { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.hero-tag {
  font-size: 11px; font-weight: 700; padding: 3px 8px; border-radius: var(--radius-sm);
  background: rgba(0,0,0,0.65); backdrop-filter: blur(8px);
  border: 1px solid rgba(255,255,255,0.15); color: white; text-transform: uppercase;
}
.hero-tag.rating { background: rgba(245, 158, 11, 0.2); color: #fbbf24; border-color: rgba(245, 158, 11, 0.4); }
.hero-title { font-size: 32px; font-weight: 800; letter-spacing: -0.5px; margin-bottom: 8px; line-height: 1.15; }
.hero-synopsis { font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.hero-btn-row { display: flex; gap: 10px; }
.btn-hero-req {
  background: var(--accent-primary); color: white; border: none; border-radius: var(--radius-md);
  padding: 10px 20px; font-size: 13px; font-weight: 700; cursor: pointer;
  box-shadow: 0 4px 14px var(--accent-glow); transition: 0.2s; display: flex; align-items: center; gap: 6px;
}
.btn-hero-req:hover { filter: brightness(1.15); }
.btn-hero-info {
  background: rgba(255,255,255,0.1); color: white; border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); padding: 10px 18px; font-size: 13px; font-weight: 600;
  cursor: pointer; backdrop-filter: blur(10px); transition: 0.2s;
}

/* Media Grid Layout (Overseerr Responsive Cards) */
.section-wrapper { max-width: 1280px; margin: 16px auto 12px auto; padding: 0 16px; }
.section-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 14px; }
.section-title { font-size: 18px; font-weight: 700; letter-spacing: -0.3px; }
.section-sub { font-size: 12px; color: var(--text-dim); }

.media-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
@media (min-width: 540px) {
  .media-grid { grid-template-columns: repeat(3, 1fr); gap: 14px; }
}
@media (min-width: 768px) {
  .media-grid { grid-template-columns: repeat(4, 1fr); gap: 16px; }
}
@media (min-width: 1024px) {
  .media-grid { grid-template-columns: repeat(5, 1fr); gap: 18px; }
}
@media (min-width: 1280px) {
  .media-grid { grid-template-columns: repeat(6, 1fr); gap: 20px; }
}

/* Overseerr Media Card */
.media-card {
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); overflow: hidden; display: flex; flex-direction: column;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  cursor: pointer; position: relative;
}
.media-card:hover {
  transform: translateY(-4px); border-color: var(--border-active);
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.45);
}
.card-poster-frame {
  width: 100%; aspect-ratio: 2/3; background: #0c101a;
  position: relative; overflow: hidden;
}
.card-poster-img {
  width: 100%; height: 100%; object-fit: cover; display: block;
  transition: transform 0.3s ease;
}
.media-card:hover .card-poster-img { transform: scale(1.03); }

/* Badges on Poster */
.badge-top-left {
  position: absolute; top: 8px; left: 8px;
  font-size: 9px; font-weight: 700; padding: 3px 6px; border-radius: var(--radius-sm);
  background: rgba(0,0,0,0.75); backdrop-filter: blur(6px); color: white;
  text-transform: uppercase; letter-spacing: 0.3px;
}
.badge-top-right {
  position: absolute; top: 8px; right: 8px;
  font-size: 10px; font-weight: 700; padding: 3px 6px; border-radius: var(--radius-sm);
  background: rgba(0,0,0,0.75); backdrop-filter: blur(6px); color: #fbbf24;
}
.badge-status {
  position: absolute; bottom: 8px; left: 8px; right: 8px;
  font-size: 10px; font-weight: 700; padding: 4px 6px; border-radius: var(--radius-sm);
  text-align: center; text-transform: uppercase; letter-spacing: 0.3px;
}
.status-available { background: rgba(16, 185, 129, 0.85); color: white; backdrop-filter: blur(4px); }
.status-requested { background: rgba(99, 102, 241, 0.85); color: white; backdrop-filter: blur(4px); }
.status-downloading { background: rgba(0, 164, 220, 0.85); color: white; backdrop-filter: blur(4px); }

.card-content { padding: 10px; display: flex; flex-direction: column; flex: 1; }
.card-title {
  font-size: 13px; font-weight: 600; line-height: 1.3; margin-bottom: 3px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.card-meta-line { font-size: 11px; color: var(--text-muted); margin-bottom: 6px; display: flex; justify-content: space-between; }
.card-genres-line { font-size: 10px; color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 8px; }
.card-btn-request {
  margin-top: auto; background: var(--bg-elevated); border: 1px solid var(--border-subtle);
  color: var(--accent-primary); border-radius: var(--radius-sm); padding: 7px 0; font-size: 12px; font-weight: 700;
  text-align: center; cursor: pointer; transition: all 0.2s;
}
.card-btn-request:hover { background: var(--accent-primary); color: white; border-color: var(--accent-primary); }
.card-btn-request.is-available { background: rgba(16, 185, 129, 0.12); color: #34d399; border-color: rgba(16, 185, 129, 0.3); }

/* Overseerr Media Detail Modal */
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.82);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  z-index: 200; display: none; align-items: center; justify-content: center;
  padding: 16px; opacity: 0; transition: opacity 0.25s ease;
}
.modal-overlay.open { display: flex; opacity: 1; }
.modal-card {
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg); width: 100%; max-width: 680px;
  max-height: 90vh; overflow-y: auto; position: relative;
  box-shadow: 0 20px 50px rgba(0,0,0,0.7);
  transform: scale(0.96); transition: transform 0.25s ease;
}
.modal-overlay.open .modal-card { transform: scale(1); }
.modal-close-icon {
  position: absolute; top: 12px; right: 12px; z-index: 10;
  width: 32px; height: 32px; border-radius: 50%;
  background: rgba(0,0,0,0.6); color: white; border: 1px solid rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center; font-size: 14px;
  cursor: pointer; backdrop-filter: blur(8px);
}
.modal-hero-frame { position: relative; width: 100%; height: 210px; background: #0c101a; }
.modal-hero-img { width: 100%; height: 100%; object-fit: cover; }
.modal-hero-shade {
  position: absolute; inset: 0;
  background: linear-gradient(180deg, rgba(0,0,0,0.1) 0%, var(--bg-surface) 100%);
}
.modal-inner { padding: 0 20px 24px 20px; position: relative; margin-top: -65px; z-index: 5; }
.modal-header-row { display: flex; gap: 16px; margin-bottom: 14px; }
.modal-poster-thumb {
  width: 105px; aspect-ratio: 2/3; object-fit: cover; border-radius: var(--radius-md);
  box-shadow: 0 8px 24px rgba(0,0,0,0.6); border: 1px solid rgba(255,255,255,0.15); flex-shrink: 0;
}
.modal-title-col { display: flex; flex-direction: column; justify-content: flex-end; }
.modal-heading { font-size: 22px; font-weight: 800; line-height: 1.2; margin-bottom: 4px; }
.modal-tagline { font-size: 12px; color: var(--accent-secondary); font-style: italic; margin-bottom: 6px; }
.modal-metadata { display: flex; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--text-muted); align-items: center; }
.meta-chip { background: var(--bg-elevated); padding: 2px 7px; border-radius: 4px; border: 1px solid var(--border-subtle); }
.modal-synopsis { font-size: 13px; line-height: 1.6; color: #cbd5e1; margin-bottom: 14px; }
.modal-genre-pills { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 18px; }
.genre-pill { font-size: 11px; padding: 3px 8px; border-radius: var(--radius-sm); background: var(--bg-elevated); border: 1px solid var(--border-subtle); color: var(--text-muted); }

/* Overseerr Request Control Box */
.request-control-box {
  background: var(--bg-elevated); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); padding: 14px; margin-bottom: 16px;
}
.control-box-title { font-size: 12px; font-weight: 700; text-transform: uppercase; color: var(--text-dim); margin-bottom: 10px; letter-spacing: 0.5px; }
.control-row { display: flex; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.control-col { flex: 1; min-width: 140px; }
.control-label { font-size: 11px; color: var(--text-muted); margin-bottom: 4px; display: block; font-weight: 600; }
.control-select {
  width: 100%; background: var(--bg-surface); border: 1px solid var(--border-subtle);
  color: var(--text-main); padding: 8px 10px; border-radius: var(--radius-sm); font-size: 12px;
  outline: none; transition: border-color 0.2s;
}
.control-select:focus { border-color: var(--accent-primary); }

/* TV Seasons Selector */
.seasons-section { margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--border-subtle); }
.seasons-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.seasons-title { font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; }
.btn-select-all { font-size: 11px; color: var(--accent-secondary); background: none; border: none; cursor: pointer; }
.seasons-grid { display: flex; flex-wrap: wrap; gap: 8px; max-height: 140px; overflow-y: auto; }
.season-chip {
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm); padding: 6px 10px; font-size: 11px;
  display: flex; align-items: center; gap: 6px; cursor: pointer; user-select: none;
}
.season-chip.selected { border-color: var(--accent-primary); background: rgba(99, 102, 241, 0.15); color: white; }

.modal-submit-row { display: flex; gap: 10px; }
.btn-modal-request {
  flex: 1; background: var(--accent-primary); color: white; border: none;
  border-radius: var(--radius-md); padding: 12px; font-size: 14px; font-weight: 700;
  cursor: pointer; box-shadow: 0 4px 14px var(--accent-glow); transition: 0.2s;
}
.btn-modal-request:disabled { opacity: 0.5; cursor: not-allowed; }

/* Active Downloads Section (Transmission) */
.downloads-wrapper { max-width: 960px; margin: 12px auto; padding: 0 16px; }
.dl-item {
  background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); padding: 14px; margin-bottom: 10px;
}
.dl-head-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 8px; }
.dl-name { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.dl-pct { font-size: 13px; font-weight: 700; color: var(--accent-secondary); }
.dl-track { width: 100%; height: 6px; background: rgba(255,255,255,0.08); border-radius: 3px; overflow: hidden; margin-bottom: 10px; }
.dl-fill { height: 100%; background: var(--accent-secondary); border-radius: 3px; transition: width 0.3s ease; }
.dl-fill.paused { background: var(--warning); }
.dl-foot-row { display: flex; justify-content: space-between; align-items: center; font-size: 11px; color: var(--text-muted); flex-wrap: wrap; gap: 8px; }
.dl-btn-group { display: flex; gap: 6px; }
.btn-dl-act {
  background: var(--bg-elevated); color: var(--text-main); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm); padding: 4px 10px; font-size: 11px; font-weight: 600; cursor: pointer; transition: 0.2s;
}
.btn-dl-act:hover { border-color: var(--accent-secondary); color: var(--accent-secondary); }
.btn-dl-act.danger:hover { border-color: var(--danger); color: #f87171; background: rgba(239, 68, 68, 0.1); }

/* Toast Notifications */
.toast-notice {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  background: var(--bg-elevated); color: white; border: 1px solid var(--border-active);
  padding: 10px 22px; border-radius: 999px; font-size: 13px; font-weight: 600;
  box-shadow: 0 10px 25px rgba(0,0,0,0.5); z-index: 300; opacity: 0;
  pointer-events: none; transition: opacity 0.3s ease;
}
.toast-notice.show { opacity: 1; }
</style>
</head>
<body>

<!-- Header -->
<header>
  <div class="header-left">
    <div class="header-brand" onclick="switchNav('trending')">
      <div class="logo-icon">S</div>
      <div>
        <div class="brand-title">Jellyseerr</div>
      </div>
    </div>
    <div class="nav-links">
      <button class="nav-link-btn active" id="navTrending" onclick="switchNav('trending')">Discover</button>
      <button class="nav-link-btn" id="navMovies" onclick="switchNav('movies')">Movies</button>
      <button class="nav-link-btn" id="navTv" onclick="switchNav('tv')">TV Series</button>
      <button class="nav-link-btn" id="navUpcoming" onclick="switchNav('upcoming')">Upcoming</button>
      <button class="nav-link-btn" id="navDownloads" onclick="switchNav('downloads')">Downloads (<span id="dlNavCount">0</span>)</button>
    </div>
  </div>
  <div class="stack-indicators" id="stackIndicators">
    <div class="stack-pill" id="pillJf"><span class="stack-dot"></span>JF</div>
    <div class="stack-pill" id="pillRadarr"><span class="stack-dot"></span>Radarr</div>
    <div class="stack-pill" id="pillSonarr"><span class="stack-dot"></span>Sonarr</div>
    <div class="stack-pill" id="pillTr"><span class="stack-dot"></span>Tr</div>
  </div>
</header>

<!-- Mobile Navigation Chips -->
<div class="mobile-nav-chips">
  <button class="chip-btn active" id="chipTrending" onclick="switchNav('trending')">Discover</button>
  <button class="chip-btn" id="chipMovies" onclick="switchNav('movies')">Movies</button>
  <button class="chip-btn" id="chipTv" onclick="switchNav('tv')">TV Series</button>
  <button class="chip-btn" id="chipUpcoming" onclick="switchNav('upcoming')">Upcoming</button>
  <button class="chip-btn" id="chipDownloads" onclick="switchNav('downloads')">Downloads</button>
</div>

<!-- Search Bar -->
<div class="search-container">
  <div class="search-box">
    <span class="search-tag">SEARCH</span>
    <input type="text" id="searchInput" class="search-input" placeholder="Search movies, TV shows, or anime..." autocomplete="off">
    <button id="searchClear" class="search-clear" onclick="clearSearch()">X</button>
  </div>
</div>

<!-- Overseerr Hero Spotlight Banner -->
<div class="hero-spotlight" id="heroSpotlight" style="display:none;">
  <img class="hero-backdrop" id="heroBackdrop" src="" alt="Spotlight" onerror="this.style.display='none'">
  <div class="hero-gradient"></div>
  <div class="hero-content">
    <div class="hero-tags">
      <span class="hero-tag" id="heroTypeTag">Movie</span>
      <span class="hero-tag rating" id="heroRatingTag">Rating: 8.5</span>
      <span class="hero-tag" id="heroYearTag">2024</span>
    </div>
    <div class="hero-title" id="heroTitle">Featured Title</div>
    <div class="hero-synopsis" id="heroSynopsis">Synopsis here...</div>
    <div class="hero-btn-row">
      <button class="btn-hero-req" id="heroBtnRequest" onclick="heroRequestMedia()">Request Media</button>
      <button class="btn-hero-info" id="heroBtnDetails" onclick="heroOpenDetails()">View Details</button>
    </div>
  </div>
</div>

<!-- Media Catalog Section -->
<div id="catalogSection">
  <div class="section-wrapper">
    <div class="section-head">
      <span class="section-title" id="catalogHeading">Trending This Week</span>
      <span class="section-sub" id="catalogCount"></span>
    </div>
    <div class="media-grid" id="mediaGrid">
      <div style="grid-column: 1/-1; text-align: center; padding: 40px 0; color: var(--text-muted);">
        Loading media catalog...
      </div>
    </div>
  </div>
</div>

<!-- Active Downloads Section (Transmission) -->
<div class="downloads-wrapper" id="downloadsSection" style="display:none;">
  <div class="section-head" style="margin-bottom:12px;">
    <span class="section-title">Active BitTorrent Downloads</span>
    <span class="section-sub" id="totalSpeedLabel">0.0 MB/s</span>
  </div>
  <div id="downloadsList">
    <div style="text-align: center; padding: 30px; color: var(--text-dim);">No active downloads in Transmission.</div>
  </div>
</div>

<!-- Overseerr Request / Details Modal -->
<div class="modal-overlay" id="modalOverlay" onclick="handleModalOverlayClick(event)">
  <div class="modal-card" id="modalCard">
    <button class="modal-close-icon" onclick="closeMediaModal()">X</button>
    <div class="modal-hero-frame">
      <img class="modal-hero-img" id="modalHeroBackdrop" src="" alt="" onerror="this.style.display='none'">
      <div class="modal-hero-shade"></div>
    </div>
    <div class="modal-inner">
      <div class="modal-header-row">
        <img class="modal-poster-thumb" id="modalPosterThumb" src="" alt="Poster" onerror="handleImgError(this, 'Poster')">
        <div class="modal-title-col">
          <div class="modal-heading" id="modalTitle">Title</div>
          <div class="modal-tagline" id="modalTagline"></div>
          <div class="modal-metadata">
            <span class="meta-chip" id="modalMetaYear">2024</span>
            <span class="meta-chip" id="modalMetaCert">PG-13</span>
            <span class="meta-chip" id="modalMetaRuntime">2h 15m</span>
            <span class="meta-chip" style="color:#fbbf24;" id="modalMetaRating">Rating: 8.4</span>
          </div>
        </div>
      </div>
      <div class="modal-genre-pills" id="modalGenres"></div>
      <div class="modal-synopsis" id="modalOverview">Overview...</div>

      <!-- Overseerr Request Configuration Box -->
      <div class="request-control-box" id="requestControlBox">
        <div class="control-box-title">Request Configuration</div>
        <div class="control-row">
          <div class="control-col">
            <label class="control-label">Quality Profile</label>
            <select class="control-select" id="selQualityProfile">
              <option value="4">HD-1080p</option>
              <option value="5">Ultra-HD (4K)</option>
              <option value="1">Any</option>
              <option value="3">HD-720p</option>
            </select>
          </div>
          <div class="control-col">
            <label class="control-label">Root Storage Path</label>
            <select class="control-select" id="selRootFolder">
              <option value="">Default Service Path</option>
            </select>
          </div>
        </div>

        <!-- TV Seasons Multi-Select (for TV Shows only) -->
        <div class="seasons-section" id="tvSeasonsSection" style="display:none;">
          <div class="seasons-head">
            <span class="seasons-title">Select Seasons to Request</span>
            <button type="button" class="btn-select-all" onclick="toggleSelectAllSeasons()">Select All</button>
          </div>
          <div class="seasons-grid" id="tvSeasonsGrid"></div>
        </div>
      </div>

      <div class="modal-submit-row">
        <button class="btn-modal-request" id="btnModalRequest" onclick="submitMediaRequest()">Request Media</button>
      </div>
    </div>
  </div>
</div>

<!-- Toast -->
<div class="toast-notice" id="toastNotice"></div>

<script>
let currentNav = 'trending';
let activeCatalog = [];
let libraryMovies = [];
let librarySeries = [];
let currentHero = null;
let currentModal = null;
let radarrQualityProfiles = [];
let sonarrQualityProfiles = [];
let radarrRootFolders = [];
let sonarrRootFolders = [];
let searchDebounceTimer = null;

// SVG Fallback Poster Generator
function generateSvgPoster(title, year) {
  const safeTitle = (title || 'Media').replace(/[<>&"]/g, '');
  const safeYear = year || '';
  return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(\`
    <svg xmlns="http://www.w3.org/2000/svg" width="300" height="450" viewBox="0 0 300 450">
      <defs>
        <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#121520" />
          <stop offset="100%" stop-color="#1B2030" />
        </linearGradient>
      </defs>
      <rect width="100%" height="100%" fill="url(#bg)" />
      <rect x="15" y="15" width="270" height="420" rx="8" fill="none" stroke="rgba(255,255,255,0.08)" stroke-width="2"/>
      <circle cx="150" cy="180" r="45" fill="none" stroke="#6366f1" stroke-width="3" opacity="0.4"/>
      <text x="150" y="190" fill="#6366f1" font-size="28" font-family="sans-serif" font-weight="bold" text-anchor="middle">PLAY</text>
      <text x="150" y="270" fill="#f8fafc" font-size="16" font-family="sans-serif" font-weight="bold" text-anchor="middle">\${safeTitle.substring(0, 24)}</text>
      <text x="150" y="295" fill="#94a3b8" font-size="13" font-family="sans-serif" text-anchor="middle">\${safeYear}</text>
    </svg>
  \`);
}

function handleImgError(img, title, year) {
  img.onerror = null;
  img.src = generateSvgPoster(title, year);
}

// Initialize on Load
window.addEventListener('DOMContentLoaded', () => {
  setupSearch();
  loadProfilesAndFolders();
  loadLibraryState();
  loadCatalog('trending');
  pollStackHealth();
  setInterval(pollStackHealth, 8000);
  setInterval(loadLibraryState, 30000);
});

function pollStackHealth() {
  fetch('/api/v1/stack')
    .then(r => r.json())
    .then(data => {
      setStackPill('pillJf', data.jellyfin);
      setStackPill('pillRadarr', data.radarr);
      setStackPill('pillSonarr', data.sonarr);
      setStackPill('pillTr', data.transmission);
    })
    .catch(() => {});
}

function setStackPill(id, isOnline) {
  const el = document.getElementById(id);
  if (!el) return;
  if (isOnline) el.classList.add('online');
  else el.classList.remove('online');
}

function loadProfilesAndFolders() {
  fetch('/api/v1/quality-profiles')
    .then(r => r.json())
    .then(data => {
      radarrQualityProfiles = data.radarr || [];
      sonarrQualityProfiles = data.sonarr || [];
    })
    .catch(() => {});

  fetch('/api/v1/root-folders')
    .then(r => r.json())
    .then(data => {
      radarrRootFolders = data.radarr || [];
      sonarrRootFolders = data.sonarr || [];
    })
    .catch(() => {});
}

function loadLibraryState() {
  fetch('/api/v1/library')
    .then(r => r.json())
    .then(data => {
      libraryMovies = data.movies || [];
      librarySeries = data.series || [];
      if (activeCatalog.length > 0) renderGrid(activeCatalog);
    })
    .catch(() => {});
}

function switchNav(nav) {
  currentNav = nav;
  document.querySelectorAll('.nav-link-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.chip-btn').forEach(b => b.classList.remove('active'));

  const navMap = { trending: 'Trending', movies: 'Movies', tv: 'Tv', upcoming: 'Upcoming', downloads: 'Downloads' };
  const targetId = navMap[nav];
  if (targetId) {
    const btn = document.getElementById('nav' + targetId);
    if (btn) btn.classList.add('active');
    const chip = document.getElementById('chip' + targetId);
    if (chip) chip.classList.add('active');
  }

  const catalogSec = document.getElementById('catalogSection');
  const downloadsSec = document.getElementById('downloadsSection');
  const heroBanner = document.getElementById('heroSpotlight');

  if (nav === 'downloads') {
    catalogSec.style.display = 'none';
    heroBanner.style.display = 'none';
    downloadsSec.style.display = 'block';
    loadDownloads();
  } else {
    downloadsSec.style.display = 'none';
    catalogSec.style.display = 'block';
    loadCatalog(nav);
  }
}

function loadCatalog(category) {
  const headings = {
    trending: 'Trending Movies & Series',
    movies: 'Popular Movies (2024-2026)',
    tv: 'Popular TV Series',
    upcoming: 'Upcoming Releases (2025-2026)'
  };
  document.getElementById('catalogHeading').innerText = headings[category] || 'Media Catalog';
  document.getElementById('mediaGrid').innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding: 40px 0; color: var(--text-muted);">Loading media...</div>';

  fetch('/api/v1/discover/' + category)
    .then(r => r.json())
    .then(data => {
      const items = (data && data.results) ? data.results : (Array.isArray(data) ? data : []);
      activeCatalog = items;
      renderGrid(items);
      if (items.length > 0 && category === 'trending') {
        setupHeroSpotlight(items[0]);
      } else {
        document.getElementById('heroSpotlight').style.display = 'none';
      }
    })
    .catch(err => {
      document.getElementById('mediaGrid').innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding: 40px 0; color: var(--danger);">Failed to load media catalog: ' + err.message + '</div>';
    });
}

function getItemLibraryStatus(item) {
  if (item.type === 'movie') {
    const match = libraryMovies.find(m => (item.tmdbId && m.tmdbId === item.tmdbId) || (m.title && m.title.toLowerCase() === (item.title || '').toLowerCase()));
    if (match) {
      if (match.hasFile) return { label: 'Available', cssClass: 'status-available', isAvailable: true };
      return { label: 'Processing', cssClass: 'status-downloading', isAvailable: false };
    }
  } else if (item.type === 'tv') {
    const match = librarySeries.find(s => (item.tvdbId && s.tvdbId === item.tvdbId) || (s.title && s.title.toLowerCase() === (item.title || '').toLowerCase()));
    if (match) {
      if (match.percentOfEpisodes === 100 || (match.statistics && match.statistics.percentOfEpisodes === 100)) {
        return { label: 'Available', cssClass: 'status-available', isAvailable: true };
      }
      return { label: 'Requested', cssClass: 'status-requested', isAvailable: false };
    }
  }
  return null;
}

function renderGrid(items) {
  const grid = document.getElementById('mediaGrid');
  document.getElementById('catalogCount').innerText = items.length + ' titles';

  if (!items || items.length === 0) {
    grid.innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding: 40px 0; color: var(--text-dim);">No media titles found.</div>';
    return;
  }

  let html = '';
  items.forEach((item, idx) => {
    const safeTitle = (item.title || 'Untitled').replace(/"/g, '&quot;');
    const year = item.year || '';
    const rating = item.rating ? (Number(item.rating).toFixed(1)) : '8.0';
    const typeLabel = (item.type === 'tv' ? 'TV' : 'MOVIE');
    const genres = (item.genres && Array.isArray(item.genres)) ? item.genres.slice(0, 2).join(' • ') : '';
    const poster = item.remotePoster || item.backdrop || generateSvgPoster(safeTitle, year);
    const libStatus = getItemLibraryStatus(item);

    let statusPillHtml = '';
    let btnText = 'Request';
    let btnClass = 'card-btn-request';

    if (libStatus) {
      statusPillHtml = \`<div class="badge-status \${libStatus.cssClass}">\${libStatus.label}</div>\`;
      if (libStatus.isAvailable) {
        btnText = 'Available';
        btnClass += ' is-available';
      } else {
        btnText = libStatus.label;
      }
    }

    html += \`
      <div class="media-card" onclick="openMediaModalByIndex(\${idx})">
        <div class="card-poster-frame">
          <img class="card-poster-img" src="\${poster}" alt="\${safeTitle}" loading="lazy" onerror="handleImgError(this, '\${safeTitle.replace(/'/g, "\\\\'")}', '\${year}')">
          <span class="badge-top-left">\${typeLabel}</span>
          <span class="badge-top-right">★ \${rating}</span>
          \${statusPillHtml}
        </div>
        <div class="card-content">
          <div class="card-title" title="\${safeTitle}">\${safeTitle}</div>
          <div class="card-meta-line">
            <span>\${year}</span>
            <span>\${item.runtime || ''}</span>
          </div>
          <div class="card-genres-line">\${genres}</div>
          <button class="\${btnClass}" onclick="event.stopPropagation(); openMediaModalByIndex(\${idx})">\${btnText}</button>
        </div>
      </div>
    \`;
  });

  grid.innerHTML = html;
}

function setupHeroSpotlight(item) {
  if (!item) return;
  currentHero = item;
  const banner = document.getElementById('heroSpotlight');
  document.getElementById('heroBackdrop').src = item.backdrop || item.remotePoster || '';
  document.getElementById('heroTypeTag').innerText = (item.type === 'tv' ? 'TV Series' : 'Movie');
  document.getElementById('heroRatingTag').innerText = 'Rating: ' + (item.rating ? Number(item.rating).toFixed(1) : '8.5');
  document.getElementById('heroYearTag').innerText = item.year || '2024';
  document.getElementById('heroTitle').innerText = item.title || '';
  document.getElementById('heroSynopsis').innerText = item.overview || '';
  banner.style.display = 'flex';
}

function heroOpenDetails() {
  if (currentHero) openMediaModal(currentHero);
}

function heroRequestMedia() {
  if (currentHero) openMediaModal(currentHero);
}

function openMediaModalByIndex(idx) {
  if (activeCatalog[idx]) {
    openMediaModal(activeCatalog[idx]);
  }
}

function openMediaModal(item) {
  currentModal = item;
  const modal = document.getElementById('modalOverlay');
  const safeTitle = item.title || 'Untitled';
  const year = item.year || '';
  const poster = item.remotePoster || item.backdrop || generateSvgPoster(safeTitle, year);

  document.getElementById('modalHeroBackdrop').src = item.backdrop || poster;
  const posterThumb = document.getElementById('modalPosterThumb');
  posterThumb.src = poster;
  posterThumb.onerror = () => handleImgError(posterThumb, safeTitle, year);

  document.getElementById('modalTitle').innerText = safeTitle;
  document.getElementById('modalTagline').innerText = item.tagline || (item.genres ? item.genres.join(', ') : '');
  document.getElementById('modalMetaYear').innerText = year;
  document.getElementById('modalMetaCert').innerText = item.certification || (item.type === 'tv' ? 'TV-MA' : 'PG-13');
  document.getElementById('modalMetaRuntime').innerText = item.runtime || (item.type === 'tv' ? 'Series' : 'Feature');
  document.getElementById('modalMetaRating').innerText = '★ ' + (item.rating ? Number(item.rating).toFixed(1) : '8.0');
  document.getElementById('modalOverview').innerText = item.overview || 'No synopsis provided.';

  // Genres
  const genresEl = document.getElementById('modalGenres');
  genresEl.innerHTML = '';
  if (Array.isArray(item.genres)) {
    item.genres.forEach(g => {
      const p = document.createElement('span');
      p.className = 'genre-pill';
      p.innerText = g;
      genresEl.appendChild(p);
    });
  }

  // Populate Quality Profiles & Root Folders based on Media Type
  const qSelect = document.getElementById('selQualityProfile');
  qSelect.innerHTML = '';
  const profiles = (item.type === 'tv' ? sonarrQualityProfiles : radarrQualityProfiles);
  if (profiles.length > 0) {
    profiles.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.innerText = p.name;
      if (p.name.toLowerCase().includes('1080p')) opt.selected = true;
      qSelect.appendChild(opt);
    });
  } else {
    qSelect.innerHTML = '<option value="4" selected>HD-1080p</option><option value="5">Ultra-HD (4K)</option><option value="1">Any</option>';
  }

  const rfSelect = document.getElementById('selRootFolder');
  rfSelect.innerHTML = '';
  const rootFolders = (item.type === 'tv' ? sonarrRootFolders : radarrRootFolders);
  if (rootFolders.length > 0) {
    rootFolders.forEach(rf => {
      const opt = document.createElement('option');
      opt.value = rf;
      opt.innerText = rf;
      rfSelect.appendChild(opt);
    });
  } else {
    const defaultPath = (item.type === 'tv' ? '/storage/emulated/0/FishbowlMedia/TV' : '/storage/emulated/0/FishbowlMedia/Movies');
    rfSelect.innerHTML = \`<option value="\${defaultPath}">\${defaultPath}</option>\`;
  }

  // TV Seasons Selector
  const tvSeasonsSec = document.getElementById('tvSeasonsSection');
  const tvSeasonsGrid = document.getElementById('tvSeasonsGrid');
  tvSeasonsGrid.innerHTML = '';

  if (item.type === 'tv') {
    tvSeasonsSec.style.display = 'block';
    const seasonsList = item.seasons || (item.raw && item.raw.seasons) || [{ seasonNumber: 1, episodeCount: 10 }];
    seasonsList.forEach(s => {
      if (s.seasonNumber === 0) return; // Ignore specials by default
      const chip = document.createElement('div');
      chip.className = 'season-chip selected';
      chip.dataset.seasonNumber = s.seasonNumber;
      chip.innerHTML = \`Season \${s.seasonNumber} \${s.episodeCount ? '(' + s.episodeCount + ' eps)' : ''}\`;
      chip.onclick = () => {
        chip.classList.toggle('selected');
      };
      tvSeasonsGrid.appendChild(chip);
    });
  } else {
    tvSeasonsSec.style.display = 'none';
  }

  // Check Library Status
  const libStatus = getItemLibraryStatus(item);
  const reqBtn = document.getElementById('btnModalRequest');
  if (libStatus && libStatus.isAvailable) {
    reqBtn.innerText = 'Available in Jellyfin';
    reqBtn.disabled = true;
  } else if (libStatus && !libStatus.isAvailable) {
    reqBtn.innerText = 'Request Status: ' + libStatus.label;
    reqBtn.disabled = false;
  } else {
    reqBtn.innerText = 'Request ' + (item.type === 'tv' ? 'Series' : 'Movie');
    reqBtn.disabled = false;
  }

  modal.classList.add('open');
}

function toggleSelectAllSeasons() {
  const chips = document.querySelectorAll('#tvSeasonsGrid .season-chip');
  const allSelected = Array.from(chips).every(c => c.classList.contains('selected'));
  chips.forEach(c => {
    if (allSelected) c.classList.remove('selected');
    else c.classList.add('selected');
  });
}

function closeMediaModal() {
  document.getElementById('modalOverlay').classList.remove('open');
  currentModal = null;
}

function handleModalOverlayClick(e) {
  if (e.target.id === 'modalOverlay') closeMediaModal();
}

function submitMediaRequest() {
  if (!currentModal) return;
  const reqBtn = document.getElementById('btnModalRequest');
  reqBtn.disabled = true;
  reqBtn.innerText = 'Submitting Request...';

  const qProfileId = parseInt(document.getElementById('selQualityProfile').value, 10);
  const rootPath = document.getElementById('selRootFolder').value;

  let selectedSeasons = [];
  if (currentModal.type === 'tv') {
    document.querySelectorAll('#tvSeasonsGrid .season-chip.selected').forEach(c => {
      selectedSeasons.push(parseInt(c.dataset.seasonNumber, 10));
    });
  }

  const payload = {
    type: currentModal.type,
    item: currentModal,
    qualityProfileId: qProfileId,
    rootFolderPath: rootPath,
    seasons: selectedSeasons
  };

  fetch('/api/v1/request', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
    .then(r => r.json())
    .then(data => {
      if (data.success) {
        showToast('Request submitted successfully! Added to automation stack.');
        reqBtn.innerText = 'Requested';
        loadLibraryState();
        setTimeout(closeMediaModal, 1200);
      } else {
        showToast('Request error: ' + (data.message || 'Unknown failure'));
        reqBtn.disabled = false;
        reqBtn.innerText = 'Retry Request';
      }
    })
    .catch(err => {
      showToast('Network error: ' + err.message);
      reqBtn.disabled = false;
      reqBtn.innerText = 'Retry Request';
    });
}

// Live Decoupled Search
function setupSearch() {
  const input = document.getElementById('searchInput');
  const clearBtn = document.getElementById('searchClear');

  input.addEventListener('input', () => {
    const query = input.value.trim();
    if (query) clearBtn.style.display = 'block';
    else clearBtn.style.display = 'none';

    clearTimeout(searchDebounceTimer);
    if (query.length < 2) {
      if (!query) loadCatalog(currentNav);
      return;
    }

    searchDebounceTimer = setTimeout(() => {
      executeSearch(query);
    }, 300);
  });
}

function clearSearch() {
  const input = document.getElementById('searchInput');
  input.value = '';
  document.getElementById('searchClear').style.display = 'none';
  loadCatalog(currentNav);
}

function executeSearch(query) {
  document.getElementById('catalogHeading').innerText = 'Search Results for "' + query + '"';
  document.getElementById('mediaGrid').innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding: 40px 0; color: var(--text-muted);">Searching across media stack...</div>';
  document.getElementById('heroSpotlight').style.display = 'none';

  fetch('/api/v1/search?q=' + encodeURIComponent(query))
    .then(r => r.json())
    .then(results => {
      activeCatalog = results || [];
      renderGrid(activeCatalog);
    })
    .catch(err => {
      document.getElementById('mediaGrid').innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding: 40px 0; color: var(--danger);">Search failed: ' + err.message + '</div>';
    });
}

// Downloads View
function loadDownloads() {
  fetch('/api/v1/downloads')
    .then(r => r.json())
    .then(torrents => {
      renderDownloads(torrents || []);
    })
    .catch(() => {});
}

function renderDownloads(torrents) {
  const list = document.getElementById('downloadsList');
  document.getElementById('dlNavCount').innerText = torrents.length;

  let totalSpeed = 0;
  torrents.forEach(t => totalSpeed += (t.rateDownload || 0));
  document.getElementById('totalSpeedLabel').innerText = (totalSpeed / 1048576).toFixed(2) + ' MB/s';

  if (torrents.length === 0) {
    list.innerHTML = '<div style="text-align: center; padding: 30px; color: var(--text-dim);">No active downloads in Transmission.</div>';
    return;
  }

  let html = '';
  torrents.forEach(t => {
    const pct = ((t.percentDone || 0) * 100).toFixed(1);
    const speed = ((t.rateDownload || 0) / 1048576).toFixed(2) + ' MB/s';
    const isPaused = t.status === 0;

    html += \`
      <div class="dl-item">
        <div class="dl-head-row">
          <div class="dl-name" title="\${t.name}">\${t.name}</div>
          <div class="dl-pct">\${pct}%</div>
        </div>
        <div class="dl-track">
          <div class="dl-fill \${isPaused ? 'paused' : ''}" style="width: \${pct}%"></div>
        </div>
        <div class="dl-foot-row">
          <span>Speed: \${speed} • \${isPaused ? 'PAUSED' : 'DOWNLOADING'}</span>
          <div class="dl-btn-group">
            <button class="btn-dl-act" onclick="toggleTorrentPause(\${t.id}, \${isPaused})">\${isPaused ? 'Resume' : 'Pause'}</button>
            <button class="btn-dl-act danger" onclick="deleteTorrentItem(\${t.id})">Delete</button>
          </div>
        </div>
      </div>
    \`;
  });
  list.innerHTML = html;
}

function toggleTorrentPause(id, isPaused) {
  const endpoint = isPaused ? '/api/v1/downloads/resume' : '/api/v1/downloads/pause';
  fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id })
  }).then(() => loadDownloads());
}

function deleteTorrentItem(id) {
  if (!confirm('Are you sure you want to remove this download and delete files?')) return;
  fetch('/api/v1/downloads/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id, deleteData: true })
  }).then(() => loadDownloads());
}

function showToast(msg) {
  const t = document.getElementById('toastNotice');
  t.innerText = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 3500);
}
</script>
</body>
</html>`;

// ─────────────────────────────────────────────────────────────────────────────
// COMPREHENSIVE REST API & ROUTING ENGINE
// ─────────────────────────────────────────────────────────────────────────────

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
        const q = (parsed.query.q || '').trim();
        const keys = getStackKeys();
        let results = [];

        if (q) {
            const queryLow = q.toLowerCase();
            // 1. Search in curated modern cache
            const localMatches = CURATED_MEDIA.filter(item =>
                (item.title && item.title.toLowerCase().includes(queryLow)) ||
                (item.overview && item.overview.toLowerCase().includes(queryLow))
            );
            results.push(...localMatches);

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

    // 11. Request Media (POST)
    if (pathname === '/api/v1/request' && req.method === 'POST') {
        let bodyStr = '';
        req.on('data', c => bodyStr += c);
        req.on('end', async () => {
            try {
                const body = JSON.parse(bodyStr);
                const keys = getStackKeys();
                const type = body.type;
                const item = body.item;

                if (type === 'movie') {
                    if (!keys.radarr) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false, message: 'Radarr API key not available' }));
                        return;
                    }
                    const rootFolders = await getRadarrRootFolders(keys.radarr);
                    const rootPath = body.rootFolderPath || rootFolders[0] || '/storage/emulated/0/FishbowlMedia/Movies';

                    const payload = {
                        title: item.title,
                        tmdbId: item.tmdbId,
                        year: item.year,
                        qualityProfileId: body.qualityProfileId || 4,
                        rootFolderPath: rootPath,
                        monitored: true,
                        addOptions: { searchForMovie: true }
                    };
                    if (item.raw) {
                        payload.titleSlug = item.raw.titleSlug || String(item.tmdbId);
                        payload.images = item.raw.images || [];
                    }

                    try {
                        const radarrRes = await fetchJson(`http://127.0.0.1:7878/api/v3/movie?apiKey=${keys.radarr}`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Api-Key': keys.radarr
                            },
                            body: payload
                        });
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: true, result: radarrRes }));
                        return;
                    } catch (err) {
                        const msg = (err.message || '').toLowerCase();
                        if (msg.includes('already exists') || msg.includes('movieexistsvalidator')) {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ success: true, message: 'Already in library!' }));
                            return;
                        }
                        throw err;
                    }
                } else if (type === 'tv') {
                    if (!keys.sonarr) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false, message: 'Sonarr API key not available' }));
                        return;
                    }
                    const rootFolders = await getSonarrRootFolders(keys.sonarr);
                    const rootPath = body.rootFolderPath || rootFolders[0] || '/storage/emulated/0/FishbowlMedia/TV';

                    let seasonsConfig = [];
                    if (Array.isArray(body.seasons) && body.seasons.length > 0) {
                        seasonsConfig = body.seasons.map(num => ({ seasonNumber: num, monitored: true }));
                    } else if (Array.isArray(item.seasons)) {
                        seasonsConfig = item.seasons.map(s => ({ seasonNumber: s.seasonNumber, monitored: true }));
                    }

                    const payload = {
                        title: item.title,
                        tvdbId: item.tvdbId,
                        year: item.year,
                        qualityProfileId: body.qualityProfileId || 4,
                        languageProfileId: 1,
                        rootFolderPath: rootPath,
                        monitored: true,
                        seasons: seasonsConfig,
                        addOptions: { searchForMissingEpisodes: true }
                    };
                    if (item.raw) {
                        payload.titleSlug = item.raw.titleSlug || String(item.tvdbId);
                        payload.images = item.raw.images || [];
                        if (seasonsConfig.length === 0 && Array.isArray(item.raw.seasons)) {
                            payload.seasons = item.raw.seasons;
                        }
                    }

                    try {
                        const sonarrRes = await fetchJson(`http://127.0.0.1:8989/api/v3/series?apiKey=${keys.sonarr}`, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                'X-Api-Key': keys.sonarr
                            },
                            body: payload
                        });
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: true, result: sonarrRes }));
                        return;
                    } catch (err) {
                        const msg = (err.message || '').toLowerCase();
                        if (msg.includes('already exists') || msg.includes('seriesexistsvalidator')) {
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ success: true, message: 'Already in library!' }));
                            return;
                        }
                        throw err;
                    }
                }
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
        res.end(HTML_APP);
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
