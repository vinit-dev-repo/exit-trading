export const state = {
    users: [],
    currentUser: null,
    refreshHandle: null,
    scheduleHandle: null,
    depthHandle: null,
    sessionHandle: null,
    newsHandle: null,
    newsRetry: null,
    holdingsFilter: 'ALL',
    sessionActive: false,
    selectedSymbol: null,
    depthPage: 0,
    depthPageSize: 3,
    depthCards: [],
    analysisBySymbol: new Map(),
    loggingScrips: [],
    loggingSearchResults: [],
    loggingHandle: null,
    loggingLayout: 'grid',
    loggingCatchup: false
};

export const STATUS = {
    SCHEDULED: 'SCHEDULED',
    EXECUTED: 'EXECUTED',
    FAILED: 'FAILED'
};
