import asyncio
from playwright.async_api import async_playwright
import pandas as pd
import os
import re
import argparse
import logging
import time
import gc

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Constants
SCREENER_CREDS = {
    "user": "vinit123@gmail.com",
    "pass": "Screener123#"
}

FINAL_COLUMNS = [
    "Exchange", "Symbol", "Token", "Industry", 
    "Promoter %", "Public %",
    "Market Cap", "Current Price", "High", "Low", "Stock P/E", 
    "Book Value", "Dividend Yield", "ROCE", "ROE", "Face Value", 
    "Pledged percentage", "Piotroski score", "Debt to equity", 
    "Avg Vol 1Wk", "Avg Vol 1Mth", "Volume", "DMA 50", "DMA 200", 
    "Qtr Sales Var", "Return over 1day", "Return over 1week", 
    "Qtr Profit Var", "Promoter holding", "Change in Prom Hold",
    "Source Url"
]

def clean_value(val):
    if not val: return ""
    # Remove Currency symbols, percentages, commas, and unit labels
    val = str(val)
    val = val.replace("₹", "").replace("%", "").replace("Cr.", "").replace(",", "")
    return val.strip()

async def run_screener_scraper(bse_path, nse_path, output_path):
    logger.info(">>> STARTING SCREENER.IN INTEGRATION <<<")
    stocks = []
    
    # Load BSE
    if bse_path and os.path.exists(bse_path):
        try:
            df_bse = pd.read_csv(bse_path)
            logger.info(f"Loaded {len(df_bse)} BSE stocks.")
            for _, row in df_bse.iterrows():
                try:
                    url = row['Url']
                    # Handle potential missing URL or different format
                    if pd.notna(url):
                        token_match = re.search(r'/(\d{6})', url)
                        if token_match:
                            token = token_match.group(1)
                            stocks.append({
                                "Exchange": "BSE",
                                "Symbol": row.get('Symbol'),
                                "Source Url": url,
                                "Token": token,
                                "Industry": row.get('Basic Industry', ''),
                                "Promoter %": row.get('Promoter %', ''),
                                "Public %": row.get('Public %', ''),
                                "ScreenerUrl": f"https://www.screener.in/company/{token}/"
                            })
                        else:
                            logger.warning(f"Could not extract token from URL: {url}")
                except Exception as e:
                    logger.warning(f"Could not parse Token for {row.get('Symbol')}: {e}")
        except Exception as e:
            logger.error(f"Error reading BSE CSV: {e}")
    else:
        logger.warning(f"BSE Path provided but file not found: {bse_path}")

    # Load NSE
    if nse_path and os.path.exists(nse_path):
        try:
            df_nse = pd.read_csv(nse_path)
            logger.info(f"Loaded {len(df_nse)} NSE stocks.")
            for _, row in df_nse.iterrows():
                symbol = row.get('Symbol')
                if pd.notna(symbol):
                    stocks.append({
                        "Exchange": "NSE",
                        "Symbol": symbol,
                        "Source Url": row.get('Url'),
                        "Token": "NULL",
                        "Industry": row.get('Industry', ''),
                        "Promoter %": row.get('Promoter %', ''),
                        "Public %": row.get('Public %', ''),
                        "ScreenerUrl": f"https://www.screener.in/company/{symbol}/"
                    })
        except Exception as e:
             logger.error(f"Error reading NSE CSV: {e}")
    else:
        logger.warning(f"NSE Path provided but file not found: {nse_path}")

    if not stocks:
        logger.error("No stocks found in CSVs. Exiting.")
        return

    logger.info(f"Total Stocks to Process: {len(stocks)}")
    
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True) # Always headless for server side
        context = await browser.new_context(user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
        page = await context.new_page()
        
        # Login
        logger.info("Logging into Screener.in...")
        try:
            await page.goto("https://www.screener.in/login/", timeout=60000)
            await asyncio.sleep(2) 
            await page.fill("input[name='username']", SCREENER_CREDS['user'])
            await page.fill("input[name='password']", SCREENER_CREDS['pass'])
            await page.click("button[type='submit']")
            await page.wait_for_load_state("networkidle")
            logger.info("Login Submitted.")
        except Exception as e:
             logger.error(f"Login Failed: {e}")
             await browser.close()
             return

        await page.close()
        
        final_results = []
        
        # Resource blocking
        async def block_media(route):
            if route.request.resource_type in ["image", "media", "font"]:
                await route.abort()
            else:
                await route.continue_()

        SAVE_INTERVAL = 5 
        
        for idx, stock in enumerate(stocks):
            logger.info(f"Processing {idx + 1}/{len(stocks)}: {stock['Exchange']} - {stock['Symbol']}...")
            
            page = await context.new_page()
            await page.route("**/*", block_media)
            
            for col in FINAL_COLUMNS:
                if col not in stock:
                    stock[col] = "" 

            retries = 3
            success = False
            
            for attempt in range(1, retries + 1):
                added_time = (attempt - 1) * 5000
                nav_timeout = 45000 + added_time
                wait_timeout = 30000 + added_time
                
                try:
                    if attempt == 1:
                        await page.goto(stock['ScreenerUrl'], timeout=nav_timeout)
                    else:
                        await page.reload(timeout=nav_timeout)
                    
                    # Check for 404
                    title = await page.title()
                    if "Page not found" in title or "404" in title:
                        logger.warning(f"Page not found for {stock['Symbol']}. Skipping.")
                        success = False
                        break 
                    
                    try:
                        await page.wait_for_selector("#top-ratios", state="visible", timeout=wait_timeout)
                        
                        ratios = await page.eval_on_selector_all(
                            "#top-ratios li", 
                            """elements => elements.map(el => {
                                let name = el.querySelector('.name').innerText.trim();
                                let val = el.querySelector('.value').innerText.trim(); 
                                return {name: name, value: val};
                            })"""
                        )
                        
                        if len(ratios) < 5: # Basic check
                             raise Exception("Incomplete data.")
                        
                        for r in ratios:
                            name = r['name']
                            val = r['value']
                            
                            if "High / Low" in name:
                                if "/" in val:
                                    parts = val.split("/")
                                    stock['High'] = clean_value(parts[0])
                                    stock['Low'] = clean_value(parts[1])
                                else:
                                    stock['High'] = clean_value(val)
                            else:
                                if name in FINAL_COLUMNS:
                                    stock[name] = clean_value(val)
                                elif name == "Pledged Percentage":
                                    stock["Pledged percentage"] = clean_value(val)
                        
                        stock['Status'] = "Found"
                        success = True
                        break 

                    except Exception as wait_err:
                        logger.warning(f"Attempt {attempt} failed for {stock['Symbol']}: {wait_err}")
                        
                except Exception as e:
                    logger.error(f"Error scraping {stock['Symbol']} (Attempt {attempt}): {e}")
            
            if not success:
               logger.error(f"Failed to scrape {stock['Symbol']}.")
               stock['Status'] = "Error/Missing"

            ordered_stock = {col: stock.get(col, "") for col in FINAL_COLUMNS}
            final_results.append(ordered_stock)
            
            await page.close()
            
            if (idx + 1) % SAVE_INTERVAL == 0:
                 pd.DataFrame(final_results).to_csv(output_path, index=False)
                 gc.collect()
            
        await browser.close()
        
        if final_results:
            pd.DataFrame(final_results).to_csv(output_path, index=False)
            logger.info(f"Report Generated: {output_path}")
        else:
             logger.warning("No results to save.")

async def main():
    parser = argparse.ArgumentParser(description='ESM Screener Scraper')
    parser.add_argument('--bse', help='Path to BSE CSV file', required=False)
    parser.add_argument('--nse', help='Path to NSE CSV file', required=False)
    parser.add_argument('--output', help='Path to Output CSV file', required=True)
    
    args = parser.parse_args()
    
    if not args.bse and not args.nse:
        print("Error: At least one of --bse or --nse must be provided.")
        return

    await run_screener_scraper(args.bse, args.nse, args.output)

if __name__ == "__main__":
    asyncio.run(main())
